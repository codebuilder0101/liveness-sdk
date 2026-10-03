package com.solistra.liveness.active

import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import kotlin.math.abs

/**
 * State tracking evaluator for active liveness challenges using MediaPipe blendshapes and head pose.
 */
class BlendshapeChallengeEvaluator(
    private val config: LivenessConfig
) {
    private var currentChallenge: LivenessChallenge? = null
    private var challengeStartTimeMs: Long = 0L

    // State tracking flags for multi-phase motions (e.g., eye blink: opened -> closed -> reopened)
    private var hasClosedEyes = false
    private var eyeClosedTimeMs = 0L
    private var smileHoldStartTimeMs = 0L
    private var mouthOpenHoldStartTimeMs = 0L
    private var headTurnPeakReached = false
    private var headNodPeakReached = false

    fun startChallenge(challenge: LivenessChallenge, timestampMs: Long) {
        currentChallenge = challenge
        challengeStartTimeMs = timestampMs
        resetChallengeState()
    }

    private fun resetChallengeState() {
        hasClosedEyes = false
        eyeClosedTimeMs = 0L
        smileHoldStartTimeMs = 0L
        mouthOpenHoldStartTimeMs = 0L
        headTurnPeakReached = false
        headNodPeakReached = false
    }

    /**
     * Evaluates the current frame against active challenge criteria.
     * @return Pair<Boolean, Float> where first is isCompleted, second is current progress (0.0 to 1.0)
     */
    fun evaluateFrame(
        blendshapes: Map<String, Float>,
        headPose: HeadPose,
        timestampMs: Long
    ): Pair<Boolean, Float> {
        val challenge = currentChallenge ?: return Pair(false, 0f)

        return when (challenge) {
            LivenessChallenge.BLINK -> evaluateBlink(blendshapes, timestampMs)
            LivenessChallenge.SMILE -> evaluateSmile(blendshapes, timestampMs)
            LivenessChallenge.OPEN_MOUTH -> evaluateOpenMouth(blendshapes, timestampMs)
            LivenessChallenge.TURN_LEFT -> evaluateHeadTurn(headPose, isLeft = true)
            LivenessChallenge.TURN_RIGHT -> evaluateHeadTurn(headPose, isLeft = false)
            LivenessChallenge.NOD_HEAD -> evaluateHeadNod(headPose)
        }
    }

    private fun evaluateBlink(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val blinkLeft = blendshapes["eyeBlinkLeft"] ?: 0f
        val blinkRight = blendshapes["eyeBlinkRight"] ?: 0f
        val isEyesClosed = blinkLeft >= config.eyeBlinkThreshold && blinkRight >= config.eyeBlinkThreshold
        val isEyesOpen = blinkLeft < 0.25f && blinkRight < 0.25f

        if (!hasClosedEyes) {
            if (isEyesClosed) {
                hasClosedEyes = true
                eyeClosedTimeMs = timestampMs
                return Pair(false, 0.5f)
            }
            val avgBlink = (blinkLeft + blinkRight) / 2.0f
            return Pair(false, (avgBlink / config.eyeBlinkThreshold) * 0.4f)
        } else {
            val closedDuration = timestampMs - eyeClosedTimeMs
            if (isEyesOpen) {
                if (closedDuration in 60..2000) {
                    return Pair(true, 1.0f)
                } else if (closedDuration > 2000) {
                    // Reset state if eyes remained closed too long so user can naturally re-blink
                    hasClosedEyes = false
                    return Pair(false, 0f)
                }
            }
            return Pair(false, 0.75f)
        }
    }

    private fun evaluateSmile(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val smileLeft = blendshapes["mouthSmileLeft"] ?: 0f
        val smileRight = blendshapes["mouthSmileRight"] ?: 0f
        val avgSmile = (smileLeft + smileRight) / 2.0f

        val isSmiling = avgSmile >= config.smileThreshold

        if (isSmiling) {
            if (smileHoldStartTimeMs == 0L) {
                smileHoldStartTimeMs = timestampMs
            }
            val heldDuration = timestampMs - smileHoldStartTimeMs
            val requiredHoldDuration = 350L // 350 ms hold required to avoid frame flickers
            val progress = (0.5f + (heldDuration.toFloat() / requiredHoldDuration) * 0.5f).coerceAtMost(1.0f)
            
            if (heldDuration >= requiredHoldDuration) {
                return Pair(true, 1.0f)
            }
            return Pair(false, progress)
        } else {
            smileHoldStartTimeMs = 0L
            val progress = (avgSmile / config.smileThreshold) * 0.5f
            return Pair(false, progress.coerceIn(0f, 0.5f))
        }
    }

    private fun evaluateOpenMouth(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val jawOpen = blendshapes["jawOpen"] ?: 0f
        val isOpen = jawOpen >= config.mouthOpenThreshold

        if (isOpen) {
            if (mouthOpenHoldStartTimeMs == 0L) {
                mouthOpenHoldStartTimeMs = timestampMs
            }
            val heldDuration = timestampMs - mouthOpenHoldStartTimeMs
            val requiredHold = 300L
            if (heldDuration >= requiredHold) {
                return Pair(true, 1.0f)
            }
            return Pair(false, 0.5f + (heldDuration.toFloat() / requiredHold) * 0.5f)
        } else {
            mouthOpenHoldStartTimeMs = 0L
            val progress = (jawOpen / config.mouthOpenThreshold) * 0.5f
            return Pair(false, progress.coerceIn(0f, 0.5f))
        }
    }

    private fun evaluateHeadTurn(headPose: HeadPose, isLeft: Boolean): Pair<Boolean, Float> {
        val targetYaw = config.headYawThresholdDegrees
        // In front camera: turning to user's left or right
        val yaw = if (isLeft) headPose.yaw else -headPose.yaw
        
        if (yaw >= targetYaw) {
            headTurnPeakReached = true
        }

        val progress = (yaw / targetYaw).coerceIn(0f, 1.0f)

        if (headTurnPeakReached) {
            // Once peak angle is reached, the challenge is successfully completed
            return Pair(true, 1.0f)
        }
        return Pair(false, progress)
    }

    private fun evaluateHeadNod(headPose: HeadPose): Pair<Boolean, Float> {
        val targetPitch = config.headPitchThresholdDegrees
        if (headPose.pitch >= targetPitch) {
            headNodPeakReached = true
            return Pair(false, 0.7f)
        }
        
        if (headNodPeakReached && abs(headPose.pitch) < 6f) {
            return Pair(true, 1.0f)
        }

        val progress = (headPose.pitch / targetPitch).coerceIn(0f, 0.6f)
        return Pair(false, progress)
    }
}
