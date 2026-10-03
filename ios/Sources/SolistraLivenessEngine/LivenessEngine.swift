import Foundation
import UIKit
import CoreMedia
import SolistraLivenessCore
import SolistraLivenessActive
import SolistraLivenessPassive

public final class LivenessEngine: NSObject, MediaPipeLandmarkerDelegate {
    public weak var delegate: LivenessDelegate?

    private let config: LivenessConfig
    private let landmarkerHelper = MediaPipeLandmarkerHelper()
    private let challengeEvaluator: ChallengeEvaluator
    private let miniFASNetClassifier = MiniFASNetClassifier()
    private let spoofSmoother: SpoofScoreSmoother
    private let engineQueue = DispatchQueue(label: "com.solistra.liveness.engineQueue")

    private var activeChallenges: [LivenessChallenge] = []
    private var completedChallenges: [LivenessChallenge] = []
    private var currentChallengeIndex: Int = 0
    private var challengeStartTime: TimeInterval = 0
    private var sessionStartTime: TimeInterval = 0

    private var currentState: LivenessState = .idle
    private var isSessionActive = false
    private var isPassiveInferencing = false
    private var bestFaceImage: UIImage?
    private var stableAlignmentFrames = 0

    public init(config: LivenessConfig = .default) {
        self.config = config
        self.challengeEvaluator = ChallengeEvaluator(config: config)
        self.spoofSmoother = SpoofScoreSmoother(
            windowSize: config.passiveFrameSampleSize,
            passThreshold: config.passiveSpoofThreshold
        )
        super.init()
        landmarkerHelper.delegate = self
    }

    public func startSession() {
        engineQueue.async { [weak self] in
            guard let self = self, !self.isSessionActive else { return }

            self.isSessionActive = true
            self.sessionStartTime = CACurrentMediaTime()
            self.currentChallengeIndex = 0
            self.completedChallenges.removeAll()
            self.spoofSmoother.reset()
            self.bestFaceImage = nil
            self.stableAlignmentFrames = 0
            self.isPassiveInferencing = false

            if !self.config.challenges.isEmpty {
                self.activeChallenges = self.config.challenges
            } else {
                self.activeChallenges = Array(LivenessChallenge.allCases.shuffled().prefix(self.config.randomChallengeCount))
            }

            self.updateState(.faceAlignment(
                message: "Center your face in the oval",
                isFaceDetected: false,
                isCentered: false,
                isAppropriateDistance: false
            ))
        }
    }

    public func processVideoFrame(pixelBuffer: CVPixelBuffer, timestamp: TimeInterval) {
        landmarkerHelper.processFrame(pixelBuffer: pixelBuffer, timestamp: timestamp)
    }

    // MARK: - MediaPipeLandmarkerDelegate

    public func landmarkerHelper(
        didDetectFaceLandmarks landmarks: [[CGPoint]],
        blendshapes: [String: Float],
        headPose: HeadPoseAngles,
        faceBoundingBox: CGRect?,
        inputImage: UIImage?,
        timestamp: TimeInterval
    ) {
        engineQueue.async { [weak self] in
            guard let self = self, self.isSessionActive else { return }

            // Multiple faces check
            if landmarks.count > 1 {
                self.finishWithError(.multipleFacesDetected)
                return
            }

            guard let bbox = faceBoundingBox, let image = inputImage else {
                if case .performingChallenge = self.currentState {
                    self.updateState(.faceAlignment(
                        message: "Face lost. Please stay in the oval",
                        isFaceDetected: false,
                        isCentered: false,
                        isAppropriateDistance: false
                    ))
                }
                return
            }

            // Run Dual-Scale Passive Anti-Spoofing (Scale 2.7x and Scale 4.0x)
            self.runPassiveInference(image: image, faceBbox: bbox, headPose: headPose)

            // State Machine
            switch self.currentState {
            case .faceAlignment:
                let isCentered = abs(headPose.yaw) < 12.0 && abs(headPose.pitch) < 12.0
                if isCentered {
                    self.stableAlignmentFrames += 1
                    if self.stableAlignmentFrames > 8 {
                        self.startNextChallenge(timestamp: timestamp)
                    }
                } else {
                    self.stableAlignmentFrames = 0
                }

            case .performingChallenge(let challenge, _, _, _, _):
                let elapsed = Float(timestamp - self.challengeStartTime)
                let remaining = max(0, self.config.challengeTimeoutSeconds - elapsed)

                if elapsed >= self.config.challengeTimeoutSeconds {
                    self.finishWithError(.challengeTimeout(challenge))
                    return
                }

                let (completed, progress) = self.challengeEvaluator.evaluateFrame(
                    blendshapes: blendshapes,
                    headPose: headPose,
                    timestamp: timestamp
                )

                DispatchQueue.main.async { [weak self] in
                    self?.delegate?.livenessSession(didUpdateProgress: progress, forChallenge: challenge)
                }

                if completed {
                    self.completedChallenges.append(challenge)
                    self.currentChallengeIndex += 1
                    if self.currentChallengeIndex < self.activeChallenges.count {
                        self.startNextChallenge(timestamp: timestamp)
                    } else {
                        self.evaluateFinalLiveness()
                    }
                } else {
                    self.updateState(.performingChallenge(
                        challenge: challenge,
                        challengeIndex: self.currentChallengeIndex,
                        totalChallenges: self.activeChallenges.count,
                        progress: progress,
                        remainingSeconds: remaining
                    ))
                }

            default:
                break
            }
        }
    }

    private func runPassiveInference(image: UIImage, faceBbox: CGRect, headPose: HeadPoseAngles) {
        guard !isPassiveInferencing else { return }
        isPassiveInferencing = true

        guard let crop27 = MultiScaleCropper.cropFaceWithScale(image: image, faceBbox: faceBbox, scaleFactor: 2.7),
              let crop40 = MultiScaleCropper.cropFaceWithScale(image: image, faceBbox: faceBbox, scaleFactor: 4.0) else {
            isPassiveInferencing = false
            return
        }

        let pred27 = miniFASNetClassifier.classify(croppedBitmap: crop27)
        let pred40 = miniFASNetClassifier.classify(croppedBitmap: crop40)
        let combinedReal = (pred27.realConfidence + pred40.realConfidence) / 2.0

        spoofSmoother.addSample(realConfidence: combinedReal)

        if abs(headPose.yaw) < 8.0 && abs(headPose.pitch) < 8.0 && combinedReal > 0.8 {
            bestFaceImage = image
        }

        isPassiveInferencing = false
    }

    private func startNextChallenge(timestamp: TimeInterval) {
        let next = activeChallenges[currentChallengeIndex]
        challengeStartTime = timestamp
        challengeEvaluator.startChallenge(next, timestamp: timestamp)

        updateState(.performingChallenge(
            challenge: next,
            challengeIndex: currentChallengeIndex,
            totalChallenges: activeChallenges.count,
            progress: 0,
            remainingSeconds: config.challengeTimeoutSeconds
        ))
    }

    private func evaluateFinalLiveness() {
        updateState(.evaluatingPassive(progress: 0.9))

        let avg = spoofSmoother.getAverageScore()
        let passed = spoofSmoother.isReliablyReal()

        if passed {
            let duration = (CACurrentMediaTime() - sessionStartTime) * 1000.0
            let result = LivenessResult(
                isLive: true,
                passiveScore: avg,
                completedChallenges: completedChallenges,
                bestFaceImage: bestFaceImage,
                sessionDurationMs: duration
            )
            isSessionActive = false
            updateState(.success(result: result))
            DispatchQueue.main.async { [weak self] in
                self?.delegate?.livenessSession(didCompleteWithResult: result)
            }
        } else {
            finishWithError(.passiveAntiSpoofFailed(score: avg))
        }
    }

    private func finishWithError(_ error: LivenessError) {
        isSessionActive = false
        updateState(.failed(error: error))
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.livenessSession(didFailWithError: error)
        }
    }

    private func updateState(_ state: LivenessState) {
        self.currentState = state
        DispatchQueue.main.async { [weak self] in
            self?.delegate?.livenessSession(didUpdateState: state)
        }
    }

    public func landmarkerHelper(didFailWithError error: Error) {
        engineQueue.async { [weak self] in
            self?.finishWithError(.modelLoadFailed(error.localizedDescription))
        }
    }

    public func stopSession() {
        engineQueue.async { [weak self] in
            self?.isSessionActive = false
            self?.updateState(.idle)
        }
    }

    public func destroy() {
        stopSession()
        delegate = nil
    }
}
