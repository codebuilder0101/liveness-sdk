import Foundation
import SolistraLivenessCore

public struct HeadPoseAngles {
    public let pitch: Float
    public let yaw: Float
    public let roll: Float

    public init(pitch: Float, yaw: Float, roll: Float) {
        self.pitch = pitch
        self.yaw = yaw
        self.roll = roll
    }

    public static func fromRotationMatrix(_ matrix: [Float]) -> HeadPoseAngles {
        guard matrix.count >= 16 else {
            return HeadPoseAngles(pitch: 0, yaw: 0, roll: 0)
        }

        let m00 = matrix[0]
        let m10 = matrix[4], m11 = matrix[5], m12 = matrix[6]
        let m20 = matrix[8], m21 = matrix[9], m22 = matrix[10]

        let sy = sqrt(Double(m00 * m00 + m10 * m10))
        let isSingular = sy < 1e-6

        let pitch: Double
        let yaw: Double
        let roll: Double

        if !isSingular {
            pitch = atan2(Double(m21), Double(m22)) * (180.0 / .pi)
            yaw = atan2(Double(-m20), sy) * (180.0 / .pi)
            roll = atan2(Double(m10), Double(m00)) * (180.0 / .pi)
        } else {
            pitch = atan2(Double(-m12), Double(m11)) * (180.0 / .pi)
            yaw = atan2(Double(-m20), sy) * (180.0 / .pi)
            roll = 0
        }

        return HeadPoseAngles(pitch: Float(pitch), yaw: Float(yaw), roll: Float(roll))
    }
}

public final class ChallengeEvaluator {
    private let config: LivenessConfig
    private var currentChallenge: LivenessChallenge?
    private var challengeStartTime: TimeInterval = 0

    private var hasClosedEyes = false
    private var eyeClosedTimestamp: TimeInterval = 0
    // Rolling window for blink noise smoothing (last 4 frames)
    private var blinkScoreWindow: [Float] = []

    // ── Smile state ───────────────────────────────────────────────────
    private var smileHoldStartTime: TimeInterval = 0
    // Rolling window for smile noise smoothing (last 5 frames)
    private var smileScoreWindow: [Float] = []

    // ── Open Mouth state ───────────────────────────────────────────
    private var mouthOpenHoldStartTime: TimeInterval = 0

    // ── Head Turn state ───────────────────────────────────────────
    private var headTurnPeakReached = false
    private var headTurnPeakTimestamp: TimeInterval = 0

    // ── Head Nod state (FSM) ─────────────────────────────────────
    private enum NodPhase { case waitingForDown, waitingForReturn, complete }
    private var nodPhase: NodPhase = .waitingForDown
    private var nodPeakPitch: Float = 0

    public init(config: LivenessConfig) {
        self.config = config
    }

    public func startChallenge(_ challenge: LivenessChallenge, timestamp: TimeInterval) {
        self.currentChallenge = challenge
        self.challengeStartTime = timestamp
        resetState()
    }

    private func resetState() {
        hasClosedEyes = false
        eyeClosedTimestamp = 0
        blinkScoreWindow.removeAll()
        smileHoldStartTime = 0
        smileScoreWindow.removeAll()
        mouthOpenHoldStartTime = 0
        headTurnPeakReached = false
        headTurnPeakTimestamp = 0
        nodPhase = .waitingForDown
        nodPeakPitch = 0
    }

    public func evaluateFrame(
        blendshapes: [String: Float],
        headPose: HeadPoseAngles,
        timestamp: TimeInterval
    ) -> (isCompleted: Bool, progress: Float) {
        guard let challenge = currentChallenge else {
            return (false, 0)
        }

        switch challenge {
        case .blink:
            return evaluateBlink(blendshapes: blendshapes, timestamp: timestamp)
        case .smile:
            return evaluateSmile(blendshapes: blendshapes, timestamp: timestamp)
        case .openMouth:
            return evaluateOpenMouth(blendshapes: blendshapes, timestamp: timestamp)
        case .turnLeft:
            return evaluateHeadTurn(headPose: headPose, isLeft: true)
        case .turnRight:
            return evaluateHeadTurn(headPose: headPose, isLeft: false)
        case .nodHead:
            return evaluateHeadNod(headPose: headPose)
        }
    }

    private func evaluateBlink(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let blinkLeft = blendshapes["eyeBlinkLeft"] ?? 0
        let blinkRight = blendshapes["eyeBlinkRight"] ?? 0

        // Smooth blink scores over a rolling window (last 4 frames) to filter noise
        let rawAvg = (blinkLeft + blinkRight) / 2.0
        blinkScoreWindow.append(rawAvg)
        if blinkScoreWindow.count > 4 { blinkScoreWindow.removeFirst() }
        let smoothedBlink = blinkScoreWindow.reduce(0, +) / Float(blinkScoreWindow.count)

        // Use 85% of configured threshold on smoothed value for robustness
        let effectiveThreshold = config.eyeBlinkThreshold * 0.85
        let isEyesClosed = smoothedBlink >= effectiveThreshold
        // Wider open-eye band: 0.30 covers heavy-lidded users
        let isEyesOpen = blinkLeft < 0.30 && blinkRight < 0.30

        if !hasClosedEyes {
            if isEyesClosed {
                hasClosedEyes = true
                eyeClosedTimestamp = timestamp
                return (false, 0.5)
            }
            let progress = min(0.45, smoothedBlink / effectiveThreshold)
            return (false, progress)
        } else {
            let duration = timestamp - eyeClosedTimestamp
            if isEyesOpen {
                if duration >= 0.05 && duration <= 2.5 {
                    return (true, 1.0)
                } else if duration > 2.5 {
                    // Eyes held shut too long — reset
                    hasClosedEyes = false
                    blinkScoreWindow.removeAll()
                    return (false, 0)
                }
            }
            return (false, 0.75)
        }
    }

    private func evaluateSmile(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let smileLeft = blendshapes["mouthSmileLeft"] ?? 0
        let smileRight = blendshapes["mouthSmileRight"] ?? 0
        // Cheek raises correlate with genuine smiles and compensate for asymmetry
        let cheekLeft = blendshapes["cheekSquintLeft"] ?? 0
        let cheekRight = blendshapes["cheekSquintRight"] ?? 0

        // Use stronger side to avoid failing due to natural facial asymmetry
        let strongerSideSmile = max(smileLeft, smileRight)
        let avgCheek = (cheekLeft + cheekRight) / 2.0
        let compositeScore = strongerSideSmile * 0.70 + avgCheek * 0.30

        // Smooth over last 5 frames
        smileScoreWindow.append(compositeScore)
        if smileScoreWindow.count > 5 { smileScoreWindow.removeFirst() }
        let smoothedScore = smileScoreWindow.reduce(0, +) / Float(smileScoreWindow.count)

        let effectiveThreshold = config.smileThreshold * 0.80

        if smoothedScore >= effectiveThreshold {
            if smileHoldStartTime == 0 {
                smileHoldStartTime = timestamp
            }
            let holdDuration = timestamp - smileHoldStartTime
            let requiredHold: TimeInterval = 0.25  // 250ms
            let progress = Float(min(1.0, 0.5 + (holdDuration / requiredHold) * 0.5))
            if holdDuration >= requiredHold {
                return (true, 1.0)
            }
            return (false, progress)
        } else {
            // Hysteresis: only reset hold timer when score drops clearly below threshold
            if smoothedScore < effectiveThreshold * 0.75 {
                smileHoldStartTime = 0
            }
            let progress = (smoothedScore / effectiveThreshold) * 0.5
            return (false, max(0, min(0.5, progress)))
        }
    }

    private func evaluateOpenMouth(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let jawOpen = blendshapes["jawOpen"] ?? 0
        if jawOpen >= config.mouthOpenThreshold {
            if mouthOpenHoldStartTime == 0 {
                mouthOpenHoldStartTime = timestamp
            }
            let holdDuration = timestamp - mouthOpenHoldStartTime
            let required: TimeInterval = 0.3
            if holdDuration >= required {
                return (true, 1.0)
            }
            return (false, Float(0.5 + (holdDuration / required) * 0.5))
        } else {
            mouthOpenHoldStartTime = 0
            let progress = (jawOpen / config.mouthOpenThreshold) * 0.5
            return (false, max(0, min(0.5, progress)))
        }
    }

    private func evaluateHeadTurn(headPose: HeadPoseAngles, isLeft: Bool) -> (Bool, Float) {
        let targetYaw = config.headYawThresholdDegrees
        let currentYaw = isLeft ? headPose.yaw : -headPose.yaw

        if currentYaw >= targetYaw {
            if !headTurnPeakReached {
                headTurnPeakReached = true
                headTurnPeakTimestamp = 0  // set on next frame
            }
        }

        let progress = min(1.0, max(0.0, currentYaw / targetYaw))
        if headTurnPeakReached {
            // Require 150ms hold at peak to avoid noise-triggered completions
            if headTurnPeakTimestamp == 0 { headTurnPeakTimestamp = Date().timeIntervalSinceReferenceDate }
            let holdDuration = Date().timeIntervalSinceReferenceDate - headTurnPeakTimestamp
            if holdDuration >= 0.15 {
                return (true, 1.0)
            }
            return (false, 0.95)
        }
        return (false, progress)
    }

    private func evaluateHeadNod(headPose: HeadPoseAngles) -> (Bool, Float) {
        let targetPitch = config.headPitchThresholdDegrees
        // MediaPipe pitch convention: positive = looking DOWN (chin toward chest)
        // FSM: waitingForDown → waitingForReturn → complete

        switch nodPhase {
        case .waitingForDown:
            if headPose.pitch > nodPeakPitch { nodPeakPitch = headPose.pitch }
            if nodPeakPitch >= targetPitch {
                nodPhase = .waitingForReturn
                return (false, 0.65)
            }
            let progress = min(0.6, max(0.0, nodPeakPitch / targetPitch))
            return (false, progress)

        case .waitingForReturn:
            let returnProgress = min(1.0, max(0.65, 0.65 + (1.0 - Double(headPose.pitch / targetPitch)) * 0.35))
            if abs(headPose.pitch) < 12.0 {
                nodPhase = .complete
                return (true, 1.0)
            }
            return (false, Float(returnProgress))

        case .complete:
            return (true, 1.0)
        }
    }
}
