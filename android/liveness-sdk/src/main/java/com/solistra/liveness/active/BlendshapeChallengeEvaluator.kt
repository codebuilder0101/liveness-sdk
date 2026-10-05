package com.solistra.liveness.active

import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessConfig
import kotlin.math.abs
import java.util.ArrayDeque

/**
 * State tracking evaluator for active liveness challenges using MediaPipe blendshapes and head pose.
 */
class BlendshapeChallengeEvaluator(
    private val config: LivenessConfig
) {
    private var currentChallenge: LivenessChallenge? = null
    private var challengeStartTimeMs: Long = 0L

    // ── Blink state ──────────────────────────────────────────────────────────
    private var hasClosedEyes = false
    private var eyeClosedTimeMs = 0L
    // Rolling window of raw blink scores for noise smoothing (last 4 frames)
    private val blinkScoreWindow = ArrayDeque<Float>(4)

    // ── Smile state ──────────────────────────────────────────────────────────
    private var smileHoldStartTimeMs = 0L
    // Rolling window for smile score smoothing (last 5 frames)
    private val smileScoreWindow = ArrayDeque<Float>(5)

    // ── Open Mouth state ─────────────────────────────────────────────────────
    private var mouthOpenHoldStartTimeMs = 0L

    // ── Head Turn state ──────────────────────────────────────────────────────
    private var headTurnPeakReached = false
    private var headTurnPeakHoldStartMs = 0L

    // ── Head Nod state ───────────────────────────────────────────────────────
    // FSM: WAITING_FOR_DOWN → WAITING_FOR_RETURN → COMPLETE
    private enum class NodPhase { WAITING_FOR_DOWN, WAITING_FOR_RETURN, COMPLETE }
    private var nodPhase = NodPhase.WAITING_FOR_DOWN
    private var nodPeakPitch = 0f

    fun startChallenge(challenge: LivenessChallenge, timestampMs: Long) {
        currentChallenge = challenge
        challengeStartTimeMs = timestampMs
        resetChallengeState()
    }

    private fun resetChallengeState() {
        hasClosedEyes = false
        eyeClosedTimeMs = 0L
        blinkScoreWindow.clear()
        smileHoldStartTimeMs = 0L
        smileScoreWindow.clear()
        mouthOpenHoldStartTimeMs = 0L
        headTurnPeakReached = false
        headTurnPeakHoldStartMs = 0L
        nodPhase = NodPhase.WAITING_FOR_DOWN
        nodPeakPitch = 0f
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

        // Smooth blink scores over a short rolling window to filter per-frame noise
        val rawAvg = (blinkLeft + blinkRight) / 2.0f
        if (blinkScoreWindow.size >= 4) blinkScoreWindow.removeFirst()
        blinkScoreWindow.addLast(rawAvg)
        val smoothedBlink = blinkScoreWindow.average().toFloat()

        // Use a slightly lower threshold on the smoothed value for robustness
        val effectiveThreshold = config.eyeBlinkThreshold * 0.85f  // ~0.51 from default 0.60
        val isEyesClosed = smoothedBlink >= effectiveThreshold
        // Wider open-eye band: below 0.30 is clearly open (was 0.25, misses heavy-lidded users)
        val isEyesOpen = blinkLeft < 0.30f && blinkRight < 0.30f

        if (!hasClosedEyes) {
            if (isEyesClosed) {
                hasClosedEyes = true
                eyeClosedTimeMs = timestampMs
                return Pair(false, 0.5f)
            }
            val progress = (smoothedBlink / effectiveThreshold).coerceIn(0f, 0.45f)
            return Pair(false, progress)
        } else {
            val closedDuration = timestampMs - eyeClosedTimeMs
            if (isEyesOpen) {
                return when {
                    // Valid blink: was closed for 50ms–2500ms then reopened
                    closedDuration in 50..2500 -> Pair(true, 1.0f)
                    // Eyes were closed too long (held shut) — reset and wait for next blink
                    closedDuration > 2500 -> {
                        hasClosedEyes = false
                        blinkScoreWindow.clear()
                        Pair(false, 0f)
                    }
                    else -> Pair(false, 0.75f)
                }
            }
            return Pair(false, 0.75f)
        }
    }

    private fun evaluateSmile(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val smileLeft = blendshapes["mouthSmileLeft"] ?: 0f
        val smileRight = blendshapes["mouthSmileRight"] ?: 0f
        // Cheek raises strongly correlate with genuine smiles and compensate for mouth asymmetry
        val cheekLeft = blendshapes["cheekSquintLeft"] ?: 0f
        val cheekRight = blendshapes["cheekSquintRight"] ?: 0f

        // Composite smile score: weigh mouth corners (70%) + cheek squint (30%)
        // Use the STRONGER side to avoid failing due to natural facial asymmetry
        val strongerSideSmile = maxOf(smileLeft, smileRight)
        val avgCheek = (cheekLeft + cheekRight) / 2.0f
        val compositeScore = strongerSideSmile * 0.70f + avgCheek * 0.30f

        // Smooth over last 5 frames to eliminate single-frame noise
        if (smileScoreWindow.size >= 5) smileScoreWindow.removeFirst()
        smileScoreWindow.addLast(compositeScore)
        val smoothedScore = smileScoreWindow.average().toFloat()

        // Effective threshold is lower than raw config because we use composite + smoothed score
        // Default config.smileThreshold is 0.35; effective composite threshold ≈ 0.28
        val effectiveThreshold = config.smileThreshold * 0.80f

        val isSmiling = smoothedScore >= effectiveThreshold

        if (isSmiling) {
            if (smileHoldStartTimeMs == 0L) {
                smileHoldStartTimeMs = timestampMs
            }
            val heldDuration = timestampMs - smileHoldStartTimeMs
            // Reduced hold to 250ms — enough to confirm genuine expression, fast enough to feel responsive
            val requiredHoldDuration = 250L
            val progress = (0.5f + (heldDuration.toFloat() / requiredHoldDuration) * 0.5f).coerceAtMost(1.0f)

            if (heldDuration >= requiredHoldDuration) {
                return Pair(true, 1.0f)
            }
            return Pair(false, progress)
        } else {
            // Only reset hold timer if score drops clearly below threshold (hysteresis band)
            if (smoothedScore < effectiveThreshold * 0.75f) {
                smileHoldStartTimeMs = 0L
            }
            val progress = (smoothedScore / effectiveThreshold) * 0.5f
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
        // Sign convention from MediaPipe transformation matrix (column-major, row-major decomposition):
        // headPose.yaw > 0 → user's nose points to THEIR left (viewer's right in mirrored preview)
        // The bitmap is already horizontally flipped by CameraPreviewManager so the landmark axes
        // are NOT mirrored — yaw positive = user turns LEFT from their own perspective.
        // TURN_LEFT challenge = user turns their face to the left → yaw positive
        // TURN_RIGHT challenge = user turns their face to the right → yaw negative → we negate
        val yaw = if (isLeft) headPose.yaw else -headPose.yaw

        if (yaw >= targetYaw) {
            if (!headTurnPeakReached) {
                headTurnPeakReached = true
                headTurnPeakHoldStartMs = 0L  // will be set on next frame
            }
        }

        val progress = (yaw / targetYaw).coerceIn(0f, 1.0f)

        if (headTurnPeakReached) {
            // Require a brief hold at the peak angle (≥ 150ms) to avoid completing on a transient
            // overshoot caused by quantization noise in the matrix decomposition
            if (headTurnPeakHoldStartMs == 0L) {
                headTurnPeakHoldStartMs = System.currentTimeMillis()
            }
            val holdMs = System.currentTimeMillis() - headTurnPeakHoldStartMs
            return if (holdMs >= 150L) {
                Pair(true, 1.0f)
            } else {
                Pair(false, 0.95f)  // Nearly complete — display ring almost full
            }
        }
        return Pair(false, progress)
    }

    private fun evaluateHeadNod(headPose: HeadPose): Pair<Boolean, Float> {
        val targetPitch = config.headPitchThresholdDegrees
        // MediaPipe pitch convention: positive = looking DOWN (chin toward chest)
        // A nod motion: chin drops (pitch rises) then returns to neutral (abs(pitch) < returnThresh)
        //
        // We also handle the case where pitch is NEGATIVE (head tilted back) before the nod:
        // the user may start slightly looking up. We only require the DOWNWARD phase then return.

        return when (nodPhase) {
            NodPhase.WAITING_FOR_DOWN -> {
                // Track the maximum downward angle seen so far
                if (headPose.pitch > nodPeakPitch) nodPeakPitch = headPose.pitch

                if (nodPeakPitch >= targetPitch) {
                    // Reached the required downward angle
                    nodPhase = NodPhase.WAITING_FOR_RETURN
                    Pair(false, 0.65f)
                } else {
                    val progress = (nodPeakPitch / targetPitch).coerceIn(0f, 0.6f)
                    Pair(false, progress)
                }
            }

            NodPhase.WAITING_FOR_RETURN -> {
                // Keep displaying progress while user returns to neutral
                val returnProgress = (0.65f + (1f - (headPose.pitch / targetPitch).coerceIn(0f, 1f)) * 0.35f).coerceIn(0.65f, 1f)
                // Return threshold: abs(pitch) < 12° covers natural return without requiring perfect center
                if (abs(headPose.pitch) < 12f) {
                    nodPhase = NodPhase.COMPLETE
                    Pair(true, 1.0f)
                } else {
                    Pair(false, returnProgress)
                }
            }

            NodPhase.COMPLETE -> Pair(true, 1.0f)
        }
    }
}
