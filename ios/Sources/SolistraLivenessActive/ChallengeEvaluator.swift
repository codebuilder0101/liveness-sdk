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
    private var smileHoldStartTime: TimeInterval = 0
    private var mouthOpenHoldStartTime: TimeInterval = 0
    private var headTurnPeakReached = false
    private var headNodPeakReached = false

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
        smileHoldStartTime = 0
        mouthOpenHoldStartTime = 0
        headTurnPeakReached = false
        headNodPeakReached = false
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
        let isEyesClosed = blinkLeft >= config.eyeBlinkThreshold && blinkRight >= config.eyeBlinkThreshold
        let isEyesOpen = blinkLeft < 0.25 && blinkRight < 0.25

        if !hasClosedEyes {
            if isEyesClosed {
                hasClosedEyes = true
                eyeClosedTimestamp = timestamp
                return (false, 0.5)
            }
            let avg = (blinkLeft + blinkRight) / 2.0
            return (false, min(0.4, (avg / config.eyeBlinkThreshold) * 0.4))
        } else {
            let duration = timestamp - eyeClosedTimestamp
            if isEyesOpen && duration >= 0.08 && duration <= 1.5 {
                return (true, 1.0)
            }
            return (false, 0.75)
        }
    }

    private func evaluateSmile(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let smileLeft = blendshapes["mouthSmileLeft"] ?? 0
        let smileRight = blendshapes["mouthSmileRight"] ?? 0
        let avgSmile = (smileLeft + smileRight) / 2.0

        if avgSmile >= config.smileThreshold {
            if smileHoldStartTime == 0 {
                smileHoldStartTime = timestamp
            }
            let holdDuration = timestamp - smileHoldStartTime
            let requiredHold: TimeInterval = 0.35
            let progress = Float(min(1.0, 0.5 + (holdDuration / requiredHold) * 0.5))
            if holdDuration >= requiredHold {
                return (true, 1.0)
            }
            return (false, progress)
        } else {
            smileHoldStartTime = 0
            let progress = (avgSmile / config.smileThreshold) * 0.5
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
            headTurnPeakReached = true
        }

        let progress = min(1.0, max(0.0, currentYaw / targetYaw))
        if headTurnPeakReached {
            return (true, 1.0)
        }
        return (false, progress)
    }

    private func evaluateHeadNod(headPose: HeadPoseAngles) -> (Bool, Float) {
        let targetPitch = config.headPitchThresholdDegrees
        if headPose.pitch >= targetPitch {
            headNodPeakReached = true
            return (false, 0.7)
        }
        // 10° return threshold (loosened from 6°) to reliably detect the head returning to neutral
        if headNodPeakReached && abs(headPose.pitch) < 10.0 {
            return (true, 1.0)
        }
        let progress = min(0.6, max(0.0, headPose.pitch / targetPitch))
        return (false, progress)
    }
}
