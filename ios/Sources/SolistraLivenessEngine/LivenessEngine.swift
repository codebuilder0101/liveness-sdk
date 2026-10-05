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
    private var alignmentPitchSamples: [Float] = []
    private var alignmentYawSamples: [Float] = []
    private var interChallengeStartTime: TimeInterval = 0
    private var interChallengeCenteredFrames = 0
    private var interChallengePitchSamples: [Float] = []
    private var interChallengeYawSamples: [Float] = []

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
            self.alignmentPitchSamples.removeAll()
            self.alignmentYawSamples.removeAll()
            self.interChallengeStartTime = 0
            self.interChallengeCenteredFrames = 0
            self.interChallengePitchSamples.removeAll()
            self.interChallengeYawSamples.removeAll()
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
            self.runPassiveInference(image: image, faceBbox: bbox, headPose: headPose, blendshapes: blendshapes)

            // State Machine
            switch self.currentState {
            case .faceAlignment:
                let isCentered = abs(headPose.yaw) < 18.0 && abs(headPose.pitch) < 18.0
                if isCentered {
                    self.alignmentPitchSamples.append(headPose.pitch)
                    self.alignmentYawSamples.append(headPose.yaw)
                    self.stableAlignmentFrames += 1
                    if self.stableAlignmentFrames > 6 {
                        let basePitch = self.alignmentPitchSamples.reduce(0, +) / Float(self.alignmentPitchSamples.count)
                        let baseYaw = self.alignmentYawSamples.reduce(0, +) / Float(self.alignmentYawSamples.count)
                        self.challengeEvaluator.setBaseline(pitch: basePitch, yaw: baseYaw)
                        self.startNextChallenge(timestamp: timestamp)
                    }
                } else {
                    self.stableAlignmentFrames = 0
                    self.alignmentPitchSamples.removeAll()
                    self.alignmentYawSamples.removeAll()
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
                        let next = self.activeChallenges[self.currentChallengeIndex]
                        self.interChallengeStartTime = timestamp
                        self.interChallengeCenteredFrames = 0
                        self.interChallengePitchSamples.removeAll()
                        self.interChallengeYawSamples.removeAll()
                        self.updateState(.interChallenge(
                            message: "Look straight at the camera",
                            completedChallenge: challenge,
                            nextChallenge: next,
                            completedIndex: self.currentChallengeIndex - 1,
                            totalChallenges: self.activeChallenges.count
                        ))
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

            case .interChallenge:
                let deltaPitch = headPose.pitch - self.challengeEvaluator.getBaselinePitch()
                let deltaYaw = headPose.yaw - self.challengeEvaluator.getBaselineYaw()
                let isCentered = abs(deltaYaw) <= 7.0 && abs(deltaPitch) <= 7.0
                let elapsed = timestamp - self.interChallengeStartTime

                if isCentered {
                    self.interChallengeCenteredFrames += 1
                    self.interChallengePitchSamples.append(headPose.pitch)
                    self.interChallengeYawSamples.append(headPose.yaw)
                } else {
                    self.interChallengeCenteredFrames = 0
                }

                if (elapsed >= 0.35 && self.interChallengeCenteredFrames >= 4) || elapsed >= 2.5 {
                    if !self.interChallengePitchSamples.isEmpty && !self.interChallengeYawSamples.isEmpty {
                        let refinedPitch = self.interChallengePitchSamples.reduce(0, +) / Float(self.interChallengePitchSamples.count)
                        let refinedYaw = self.interChallengeYawSamples.reduce(0, +) / Float(self.interChallengeYawSamples.count)
                        self.challengeEvaluator.setBaseline(pitch: refinedPitch, yaw: refinedYaw)
                    }
                    self.startNextChallenge(timestamp: timestamp)
                }

            default:
                break
            }
        }
    }

    private func runPassiveInference(
        image: UIImage,
        faceBbox: CGRect,
        headPose: HeadPoseAngles,
        blendshapes: [String: Float] = [:]
    ) {
        guard !isPassiveInferencing else { return }

        // Gating 1: only run passive anti-spoof on near-frontal frames (|deltaYaw| <= 6°, |deltaPitch| <= 6°)
        let deltaYaw = headPose.yaw - challengeEvaluator.getBaselineYaw()
        let deltaPitch = headPose.pitch - challengeEvaluator.getBaselinePitch()
        if abs(deltaYaw) > 6.0 || abs(deltaPitch) > 6.0 {
            return
        }

        // Gating 2: strict neutral expression check
        let blinkL = blendshapes["eyeBlinkLeft"] ?? 0
        let blinkR = blendshapes["eyeBlinkRight"] ?? 0
        let avgBlink = (blinkL + blinkR) / 2.0
        let jawOpen = blendshapes["jawOpen"] ?? 0
        let smileL = blendshapes["mouthSmileLeft"] ?? 0
        let smileR = blendshapes["mouthSmileRight"] ?? 0
        let maxSmile = max(smileL, smileR)

        if avgBlink > 0.20 || jawOpen > 0.05 || maxSmile > 0.10 {
            return
        }

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

        if abs(deltaYaw) < 6.0 && abs(deltaPitch) < 6.0 && avgBlink < 0.20 && jawOpen < 0.05 && maxSmile < 0.10 && combinedReal > 0.8 {
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
