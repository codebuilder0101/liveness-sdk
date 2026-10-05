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

    // ── Baseline Head Calibration ────────────────────────────────────────────
    private var baselinePitch: Float = 0f
    private var baselineYaw: Float = 0f

    // ── Blink state (hysteresis transition) ──────────────────────────────────
    private var closedSinceMs = -1L

    // ── Smile state (hysteresis + hold duration) ─────────────────────────────
    private var smileHoldStartTimeMs = 0L
    private var lastSmileValidTimeMs = 0L

    // ── Open Mouth state ─────────────────────────────────────────────────────
    private var mouthOpenHoldStartTimeMs = 0L
    private var lastMouthValidTimeMs = 0L

    // ── Head Turn state ──────────────────────────────────────────────────────
    private var headTurnPeakHoldStartMs = 0L

    // ── Head Nod state (FSM with baseline excursion and return) ──────────────
    private enum class NodPhase { WAITING_FOR_DOWN, WAITING_FOR_RETURN, COMPLETE }
    private var nodPhase = NodPhase.WAITING_FOR_DOWN
    private var nodPeakPitch = 0f
    private var nodStartMs = 0L

    fun setBaseline(pitch: Float, yaw: Float) {
        this.baselinePitch = pitch
        this.baselineYaw = yaw
    }

    fun getBaselinePitch(): Float = baselinePitch
    fun getBaselineYaw(): Float = baselineYaw

    fun startChallenge(challenge: LivenessChallenge, timestampMs: Long) {
        currentChallenge = challenge
        challengeStartTimeMs = timestampMs
        resetChallengeState()
    }

    private fun resetChallengeState() {
        closedSinceMs = -1L
        smileHoldStartTimeMs = 0L
        lastSmileValidTimeMs = 0L
        mouthOpenHoldStartTimeMs = 0L
        lastMouthValidTimeMs = 0L
        headTurnPeakHoldStartMs = 0L
        nodPhase = NodPhase.WAITING_FOR_DOWN
        nodPeakPitch = 0f
        nodStartMs = 0L
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
            LivenessChallenge.TURN_LEFT -> evaluateHeadTurn(headPose, isLeft = true, timestampMs = timestampMs)
            LivenessChallenge.TURN_RIGHT -> evaluateHeadTurn(headPose, isLeft = false, timestampMs = timestampMs)
            LivenessChallenge.NOD_HEAD -> evaluateHeadNod(headPose, timestampMs = timestampMs)
        }
    }

    /**
     * Blink detection using hysteresis transition:
     * - Closed when average blink score exceeds closeThreshold (~0.40).
     * - Complete when eyes reopen (blink scores drop below 0.25) within valid duration (40ms - 1500ms).
     */
    private fun evaluateBlink(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val blinkLeft = blendshapes["eyeBlinkLeft"] ?: 0f
        val blinkRight = blendshapes["eyeBlinkRight"] ?: 0f
        val avgBlink = (blinkLeft + blinkRight) / 2.0f

        val closeThreshold = (config.eyeBlinkThreshold * 0.80f).coerceIn(0.35f, 0.60f)
        val openThreshold = 0.25f

        if (closedSinceMs < 0L) {
            if (avgBlink >= closeThreshold) {
                closedSinceMs = timestampMs
                return Pair(false, 0.5f)
            }
            val progress = (avgBlink / closeThreshold).coerceIn(0f, 0.45f)
            return Pair(false, progress)
        } else {
            val duration = timestampMs - closedSinceMs
            val isReopened = (avgBlink < openThreshold) || (blinkLeft < openThreshold && blinkRight < openThreshold)

            if (isReopened) {
                closedSinceMs = -1L
                return if (duration in 40..1500) {
                    Pair(true, 1.0f)
                } else {
                    Pair(false, 0f)
                }
            } else if (duration > 2500) {
                // Eyes held closed too long (e.g. squinting or sleeping)
                closedSinceMs = -1L
                return Pair(false, 0f)
            }
            return Pair(false, 0.75f)
        }
    }

    /**
     * Smile detection with mouth corner dominance and cheek squint support.
     * Uses 150ms hold time and tolerates brief single-frame drops.
     */
    private fun evaluateSmile(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val smileLeft = blendshapes["mouthSmileLeft"] ?: 0f
        val smileRight = blendshapes["mouthSmileRight"] ?: 0f
        val cheekLeft = blendshapes["cheekSquintLeft"] ?: 0f
        val cheekRight = blendshapes["cheekSquintRight"] ?: 0f

        val strongerSmile = maxOf(smileLeft, smileRight)
        val avgCheek = (cheekLeft + cheekRight) / 2.0f
        // Mouth corners are primary (85%), cheek squints provide minor boost (15%)
        val compositeScore = strongerSmile * 0.85f + avgCheek * 0.15f

        val effectiveThreshold = (config.smileThreshold * 0.85f).coerceIn(0.25f, 0.45f)
        val requiredHold = 150L

        if (compositeScore >= effectiveThreshold) {
            if (smileHoldStartTimeMs == 0L) {
                smileHoldStartTimeMs = timestampMs
            }
            lastSmileValidTimeMs = timestampMs
            val heldDuration = timestampMs - smileHoldStartTimeMs
            val progress = (0.5f + (heldDuration.toFloat() / requiredHold) * 0.5f).coerceAtMost(1.0f)

            if (heldDuration >= requiredHold) {
                return Pair(true, 1.0f)
            }
            return Pair(false, progress)
        } else {
            // Tolerate single frame glitch (up to 120ms) before wiping hold
            if (timestampMs - lastSmileValidTimeMs > 120L) {
                smileHoldStartTimeMs = 0L
            }
            val progress = (compositeScore / effectiveThreshold) * 0.5f
            return Pair(false, progress.coerceIn(0f, 0.5f))
        }
    }

    /**
     * Open mouth detection with jawOpen threshold and 150ms hold time.
     */
    private fun evaluateOpenMouth(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val jawOpen = blendshapes["jawOpen"] ?: 0f
        val effectiveThreshold = (config.mouthOpenThreshold * 0.85f).coerceIn(0.28f, 0.50f)
        val requiredHold = 150L

        if (jawOpen >= effectiveThreshold) {
            if (mouthOpenHoldStartTimeMs == 0L) {
                mouthOpenHoldStartTimeMs = timestampMs
            }
            lastMouthValidTimeMs = timestampMs
            val heldDuration = timestampMs - mouthOpenHoldStartTimeMs
            val progress = (0.5f + (heldDuration.toFloat() / requiredHold) * 0.5f).coerceAtMost(1.0f)

            if (heldDuration >= requiredHold) {
                return Pair(true, 1.0f)
            }
            return Pair(false, progress)
        } else {
            if (timestampMs - lastMouthValidTimeMs > 120L) {
                mouthOpenHoldStartTimeMs = 0L
            }
            val progress = (jawOpen / effectiveThreshold) * 0.5f
            return Pair(false, progress.coerceIn(0f, 0.5f))
        }
    }

    /**
     * Head turn detection relative to alignment baseline yaw.
     * TURN_LEFT: user turns face left -> deltaYaw positive (or positive excursion).
     * TURN_RIGHT: user turns face right -> deltaYaw negative (or negative excursion).
     */
    private fun evaluateHeadTurn(
        headPose: HeadPose,
        isLeft: Boolean,
        timestampMs: Long
    ): Pair<Boolean, Float> {
        val deltaYaw = headPose.yaw - baselineYaw
        // In unmirrored camera frame:
        // When user looks to their left, nose moves left -> positive yaw
        // When user looks to their right, nose moves right -> negative yaw
        val turnExcursion = if (isLeft) deltaYaw else -deltaYaw
        val targetYaw = config.headYawThresholdDegrees
        val requiredHold = 100L

        if (turnExcursion >= targetYaw) {
            if (headTurnPeakHoldStartMs == 0L) {
                headTurnPeakHoldStartMs = timestampMs
            }
            val holdMs = timestampMs - headTurnPeakHoldStartMs
            val progress = (0.6f + (holdMs.toFloat() / requiredHold) * 0.4f).coerceAtMost(1.0f)
            if (holdMs >= requiredHold) {
                return Pair(true, 1.0f)
            }
            return Pair(false, progress)
        } else {
            headTurnPeakHoldStartMs = 0L
            val progress = (turnExcursion / targetYaw).coerceIn(0f, 0.6f)
            return Pair(false, progress)
        }
    }

    /**
     * Head nod detection relative to alignment baseline pitch.
     * Excursion of >= 10° from baseline, followed by return within 5° of baseline within 1.5s.
     */
    private fun evaluateHeadNod(headPose: HeadPose, timestampMs: Long): Pair<Boolean, Float> {
        val deltaPitch = headPose.pitch - baselinePitch
        val targetExcursion = config.headPitchThresholdDegrees
        val returnTolerance = 5.0f

        return when (nodPhase) {
            NodPhase.WAITING_FOR_DOWN -> {
                // MediaPipe pitch: positive = looking down
                if (deltaPitch > nodPeakPitch) nodPeakPitch = deltaPitch

                if (nodPeakPitch >= targetExcursion) {
                    nodPhase = NodPhase.WAITING_FOR_RETURN
                    nodStartMs = timestampMs
                    Pair(false, 0.65f)
                } else {
                    val progress = (nodPeakPitch / targetExcursion).coerceIn(0f, 0.6f)
                    Pair(false, progress)
                }
            }

            NodPhase.WAITING_FOR_RETURN -> {
                if (timestampMs - nodStartMs > 1500L) {
                    // Timed out waiting for return, reset
                    nodPhase = NodPhase.WAITING_FOR_DOWN
                    nodPeakPitch = 0f
                    Pair(false, 0f)
                } else if (abs(deltaPitch) <= returnTolerance) {
                    nodPhase = NodPhase.COMPLETE
                    Pair(true, 1.0f)
                } else {
                    val returnProgress = (0.65f + (1f - (deltaPitch / targetExcursion).coerceIn(0f, 1f)) * 0.35f).coerceIn(0.65f, 0.99f)
                    Pair(false, returnProgress)
                }
            }

            NodPhase.COMPLETE -> Pair(true, 1.0f)
        }
    }

    fun getDebugStatus(): String {
        return "Challenge: ${currentChallenge?.name ?: "None"}, Nod: $nodPhase (peak: ${"%.1f".format(nodPeakPitch)}°), Baseline: P=${"%.1f".format(baselinePitch)}° Y=${"%.1f".format(baselineYaw)}°"
    }
}
