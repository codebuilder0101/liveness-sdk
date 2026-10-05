import Foundation
import SolistraLivenessCore

public struct HeadPoseAngles {
    public let pitch: Float // + looking down, - looking up
    public let yaw: Float   // + looking left, - looking right
    public let roll: Float  // + tilted right, - tilted left

    public init(pitch: Float, yaw: Float, roll: Float) {
        self.pitch = pitch
        self.yaw = yaw
        self.roll = roll
    }

    public static func fromRotationMatrix(_ matrix: [Float]) -> HeadPoseAngles {
        guard matrix.count >= 16 else {
            return HeadPoseAngles(pitch: 0, yaw: 0, roll: 0)
        }

        // Column-major 4x4 matrix: R[row][col] = matrix[col * 4 + row]
        func r(_ row: Int, _ col: Int) -> Double {
            return Double(matrix[col * 4 + row])
        }

        let sx = sqrt(r(0, 0) * r(0, 0) + r(1, 0) * r(1, 0) + r(2, 0) * r(2, 0))
        let sy = sqrt(r(0, 1) * r(0, 1) + r(1, 1) * r(1, 1) + r(2, 1) * r(2, 1))
        let sz = sqrt(r(0, 2) * r(0, 2) + r(1, 2) * r(1, 2) + r(2, 2) * r(2, 2))

        guard sx >= 1e-6, sy >= 1e-6, sz >= 1e-6 else {
            return HeadPoseAngles(pitch: 0, yaw: 0, roll: 0)
        }

        let r00 = r(0, 0) / sx
        let r10 = r(1, 0) / sx
        let r20 = r(2, 0) / sx
        let r21 = r(2, 1) / sy
        let r22 = r(2, 2) / sz

        let radToDeg = 180.0 / .pi
        let pitch = atan2(r21, r22) * radToDeg
        let yaw = atan2(-r20, sqrt(r00 * r00 + r10 * r10)) * radToDeg
        let roll = atan2(r10, r00) * radToDeg

        return HeadPoseAngles(pitch: Float(pitch), yaw: Float(yaw), roll: Float(roll))
    }
}

public final class ChallengeEvaluator {
    private let config: LivenessConfig
    private var currentChallenge: LivenessChallenge?
    private var challengeStartTime: TimeInterval = 0

    // ── Baseline Head Calibration ────────────────────────────────────────────
    private var baselinePitch: Float = 0
    private var baselineYaw: Float = 0

    // ── Blink state ──────────────────────────────────────────────────────────
    private var closedSinceTimestamp: TimeInterval = -1

    // ── Smile state ──────────────────────────────────────────────────────────
    private var smileHoldStartTime: TimeInterval = 0
    private var lastSmileValidTimestamp: TimeInterval = 0

    // ── Open Mouth state ─────────────────────────────────────────────────────
    private var mouthOpenHoldStartTime: TimeInterval = 0
    private var lastMouthValidTimestamp: TimeInterval = 0

    // ── Head Turn state ──────────────────────────────────────────────────────
    private var headTurnPeakHoldStart: TimeInterval = 0

    // ── Head Nod state (Bidirectional FSM with baseline excursion and return) ──
    private enum NodPhase { case waitingForExcursion, waitingForReturn, complete }
    private enum NodDirection { case none, down, up }
    private var nodPhase: NodPhase = .waitingForExcursion
    private var nodDirection: NodDirection = .none
    private var nodPeakPitch: Float = 0
    private var nodStartPitch: Float?
    private var nodStartTimestamp: TimeInterval = 0

    public init(config: LivenessConfig) {
        self.config = config
    }

    public func setBaseline(pitch: Float, yaw: Float) {
        self.baselinePitch = pitch
        self.baselineYaw = yaw
    }

    public func getBaselinePitch() -> Float { baselinePitch }
    public func getBaselineYaw() -> Float { baselineYaw }

    public func startChallenge(_ challenge: LivenessChallenge, timestamp: TimeInterval) {
        self.currentChallenge = challenge
        self.challengeStartTime = timestamp
        resetState()
    }

    private func resetState() {
        closedSinceTimestamp = -1
        smileHoldStartTime = 0
        lastSmileValidTimestamp = 0
        mouthOpenHoldStartTime = 0
        lastMouthValidTimestamp = 0
        headTurnPeakHoldStart = 0
        nodPhase = .waitingForExcursion
        nodDirection = .none
        nodPeakPitch = 0
        nodStartPitch = nil
        nodStartTimestamp = 0
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
            return evaluateHeadTurn(headPose: headPose, isLeft: true, timestamp: timestamp)
        case .turnRight:
            return evaluateHeadTurn(headPose: headPose, isLeft: false, timestamp: timestamp)
        case .nodHead:
            return evaluateHeadNod(headPose: headPose, timestamp: timestamp)
        }
    }

    private func evaluateBlink(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let blinkLeft = blendshapes["eyeBlinkLeft"] ?? 0
        let blinkRight = blendshapes["eyeBlinkRight"] ?? 0
        let avgBlink = (blinkLeft + blinkRight) / 2.0

        let closeThreshold = max(0.35, min(0.60, config.eyeBlinkThreshold * 0.80))
        let openThreshold: Float = 0.25

        if closedSinceTimestamp < 0 {
            if avgBlink >= closeThreshold {
                closedSinceTimestamp = timestamp
                return (false, 0.5)
            }
            let progress = min(0.45, max(0.0, avgBlink / closeThreshold))
            return (false, progress)
        } else {
            let duration = timestamp - closedSinceTimestamp
            let isReopened = (avgBlink < openThreshold) || (blinkLeft < openThreshold && blinkRight < openThreshold)

            if isReopened {
                closedSinceTimestamp = -1
                if duration >= 0.04 && duration <= 1.5 {
                    return (true, 1.0)
                } else {
                    return (false, 0)
                }
            } else if duration > 2.5 {
                closedSinceTimestamp = -1
                return (false, 0)
            }
            return (false, 0.75)
        }
    }

    private func evaluateSmile(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let smileLeft = blendshapes["mouthSmileLeft"] ?? 0
        let smileRight = blendshapes["mouthSmileRight"] ?? 0
        let cheekLeft = blendshapes["cheekSquintLeft"] ?? 0
        let cheekRight = blendshapes["cheekSquintRight"] ?? 0

        let strongerSmile = max(smileLeft, smileRight)
        let avgSmile = (smileLeft + smileRight) / 2.0
        let avgCheek = (cheekLeft + cheekRight) / 2.0
        let compositeScore = max(
            strongerSmile,
            max(avgSmile + avgCheek * 0.20, strongerSmile * 0.85 + avgCheek * 0.20)
        )

        let effectiveThreshold = max(0.18, min(0.35, config.smileThreshold * 0.68))
        let requiredHold: TimeInterval = 0.10 // 100ms

        if compositeScore >= effectiveThreshold {
            if smileHoldStartTime == 0 {
                smileHoldStartTime = timestamp
            }
            lastSmileValidTimestamp = timestamp
            let heldDuration = timestamp - smileHoldStartTime
            let progress = min(1.0, Float(0.5 + (heldDuration / requiredHold) * 0.5))
            if heldDuration >= requiredHold {
                return (true, 1.0)
            }
            return (false, progress)
        } else {
            if timestamp - lastSmileValidTimestamp > 0.12 {
                smileHoldStartTime = 0
            }
            let progress = (compositeScore / effectiveThreshold) * 0.5
            return (false, max(0, min(0.5, progress)))
        }
    }

    private func evaluateOpenMouth(blendshapes: [String: Float], timestamp: TimeInterval) -> (Bool, Float) {
        let jawOpen = blendshapes["jawOpen"] ?? 0
        let lowerDownL = blendshapes["mouthLowerDownLeft"] ?? 0
        let lowerDownR = blendshapes["mouthLowerDownRight"] ?? 0
        let upperUpL = blendshapes["mouthUpperUpLeft"] ?? 0
        let upperUpR = blendshapes["mouthUpperUpRight"] ?? 0

        let avgLowerDown = (lowerDownL + lowerDownR) / 2.0
        let avgUpperUp = (upperUpL + upperUpR) / 2.0
        let lipSeparation = avgLowerDown * 0.60 + avgUpperUp * 0.40

        let compositeScore = max(jawOpen, max(jawOpen * 0.70 + lipSeparation * 0.50, lipSeparation * 0.90))
        let effectiveThreshold = max(0.16, min(0.30, config.mouthOpenThreshold * 0.65))
        let requiredHold: TimeInterval = 0.10 // 100ms

        if compositeScore >= effectiveThreshold {
            if mouthOpenHoldStartTime == 0 {
                mouthOpenHoldStartTime = timestamp
            }
            lastMouthValidTimestamp = timestamp
            let heldDuration = timestamp - mouthOpenHoldStartTime
            let progress = min(1.0, Float(0.5 + (heldDuration / requiredHold) * 0.5))
            if heldDuration >= requiredHold {
                return (true, 1.0)
            }
            return (false, progress)
        } else {
            if timestamp - lastMouthValidTimestamp > 0.12 {
                mouthOpenHoldStartTime = 0
            }
            let progress = (compositeScore / effectiveThreshold) * 0.5
            return (false, max(0, min(0.5, progress)))
        }
    }

    private func evaluateHeadTurn(
        headPose: HeadPoseAngles,
        isLeft: Bool,
        timestamp: TimeInterval
    ) -> (Bool, Float) {
        let deltaYaw = headPose.yaw - baselineYaw
        let turnExcursion = isLeft ? deltaYaw : -deltaYaw
        let targetYaw = config.headYawThresholdDegrees
        let requiredHold: TimeInterval = 0.10 // 100ms

        if turnExcursion >= targetYaw {
            if headTurnPeakHoldStart == 0 {
                headTurnPeakHoldStart = timestamp
            }
            let holdDuration = timestamp - headTurnPeakHoldStart
            let progress = min(1.0, Float(0.6 + (holdDuration / requiredHold) * 0.4))
            if holdDuration >= requiredHold {
                return (true, 1.0)
            }
            return (false, progress)
        } else {
            headTurnPeakHoldStart = 0
            let progress = min(0.6, max(0.0, turnExcursion / targetYaw))
            return (false, progress)
        }
    }

    private func evaluateHeadNod(headPose: HeadPoseAngles, timestamp: TimeInterval) -> (Bool, Float) {
        if nodStartPitch == nil {
            nodStartPitch = abs(headPose.pitch - baselinePitch) <= 6.0 ? headPose.pitch : baselinePitch
        }
        let refPitch = nodStartPitch ?? baselinePitch
        let deltaPitch = headPose.pitch - refPitch
        let targetExcursion = max(6.0, min(10.0, config.headPitchThresholdDegrees * 0.85))
        let returnTolerance: Float = 5.5

        switch nodPhase {
        case .waitingForExcursion:
            let downExcursion = deltaPitch
            let upExcursion = -deltaPitch

            if downExcursion > nodPeakPitch && downExcursion > 0 {
                nodPeakPitch = downExcursion
            } else if upExcursion > nodPeakPitch && upExcursion > 0 {
                nodPeakPitch = upExcursion
            }

            if downExcursion >= targetExcursion {
                nodPhase = .waitingForReturn
                nodDirection = .down
                nodPeakPitch = downExcursion
                nodStartTimestamp = timestamp
                return (false, 0.65)
            } else if upExcursion >= targetExcursion {
                nodPhase = .waitingForReturn
                nodDirection = .up
                nodPeakPitch = upExcursion
                nodStartTimestamp = timestamp
                return (false, 0.65)
            } else {
                let progress = min(0.6, max(0.0, nodPeakPitch / targetExcursion))
                return (false, progress)
            }

        case .waitingForReturn:
            let elapsedSincePeak = timestamp - nodStartTimestamp
            if elapsedSincePeak > 2.5 {
                nodPhase = .waitingForExcursion
                nodDirection = .none
                nodPeakPitch = 0
                nodStartPitch = headPose.pitch
                return (false, 0)
            } else {
                let isReturned: Bool
                switch nodDirection {
                case .down:
                    isReturned = deltaPitch <= returnTolerance || deltaPitch <= (nodPeakPitch * 0.40)
                case .up:
                    isReturned = -deltaPitch <= returnTolerance || -deltaPitch <= (nodPeakPitch * 0.40)
                case .none:
                    isReturned = abs(deltaPitch) <= returnTolerance
                }

                if isReturned {
                    nodPhase = .complete
                    return (true, 1.0)
                } else {
                    let currentExcursion = nodDirection == .down ? deltaPitch : -deltaPitch
                    let returnRatio = 1.0 - max(0.0, min(1.0, currentExcursion / nodPeakPitch))
                    let returnProgress = min(0.99, max(0.65, Float(0.65 + Double(returnRatio) * 0.35)))
                    return (false, returnProgress)
                }
            }

        case .complete:
            return (true, 1.0)
        }
    }
}
