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
    private var peakBlinkScore = 0f

    // ── Smile state (hysteresis + hold duration) ─────────────────────────────
    private var smileHoldStartTimeMs = 0L
    private var lastSmileValidTimeMs = 0L

    // ── Open Mouth state ─────────────────────────────────────────────────────
    private var mouthOpenHoldStartTimeMs = 0L
    private var lastMouthValidTimeMs = 0L

    // ── Head Turn state ──────────────────────────────────────────────────────
    private var headTurnPeakHoldStartMs = 0L

    // ── Head Nod state (Bidirectional FSM with baseline excursion and return) ──
    private enum class NodPhase { WAITING_FOR_EXCURSION, WAITING_FOR_RETURN, COMPLETE }
    private enum class NodDirection { NONE, DOWN, UP }
    private var nodPhase = NodPhase.WAITING_FOR_EXCURSION
    private var nodDirection = NodDirection.NONE
    private var nodPeakPitch = 0f
    private var nodStartPitch: Float? = null
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
        peakBlinkScore = 0f
        smileHoldStartTimeMs = 0L
        lastSmileValidTimeMs = 0L
        mouthOpenHoldStartTimeMs = 0L
        lastMouthValidTimeMs = 0L
        headTurnPeakHoldStartMs = 0L
        nodPhase = NodPhase.WAITING_FOR_EXCURSION
        nodDirection = NodDirection.NONE
        nodPeakPitch = 0f
        nodStartPitch = null
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
     * Blink detection using dynamic hysteresis:
     * - Closed when average/max blink score exceeds closeThreshold (~0.38 - 0.45).
     * - Complete when eyes reopen relative to baseline/peak within valid duration (40ms - 1500ms).
     */
    private fun evaluateBlink(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val blinkLeft = blendshapes["eyeBlinkLeft"] ?: 0f
        val blinkRight = blendshapes["eyeBlinkRight"] ?: 0f
        val avgBlink = (blinkLeft + blinkRight) / 2.0f
        val maxBlink = maxOf(blinkLeft, blinkRight)

        val closeThreshold = (config.eyeBlinkThreshold * 0.70f).coerceIn(0.32f, 0.48f)

        if (closedSinceMs < 0L) {
            if (avgBlink >= closeThreshold || maxBlink >= (closeThreshold * 1.15f)) {
                closedSinceMs = timestampMs
                peakBlinkScore = maxOf(avgBlink, maxBlink)
                return Pair(false, 0.5f)
            }
            val progress = (avgBlink / closeThreshold).coerceIn(0f, 0.45f)
            return Pair(false, progress)
        } else {
            peakBlinkScore = maxOf(peakBlinkScore, avgBlink, maxBlink)
            val duration = timestampMs - closedSinceMs
            val isReopened = (avgBlink <= 0.32f) || (blinkLeft <= 0.30f && blinkRight <= 0.30f) || (avgBlink <= peakBlinkScore * 0.55f)

            if (isReopened && duration >= 40L) {
                closedSinceMs = -1L
                peakBlinkScore = 0f
                return if (duration <= 1500L) {
                    Pair(true, 1.0f)
                } else {
                    Pair(false, 0f)
                }
            } else if (duration > 2500L) {
                // Eyes held closed too long
                closedSinceMs = -1L
                peakBlinkScore = 0f
                return Pair(false, 0f)
            }
            return Pair(false, 0.75f)
        }
    }

    /**
     * Smile detection with multi-blendshape support (corners, cheeks, stretch).
     * Uses 80ms hold time and tolerates brief single-frame drops.
     */
    private fun evaluateSmile(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val smileLeft = blendshapes["mouthSmileLeft"] ?: 0f
        val smileRight = blendshapes["mouthSmileRight"] ?: 0f
        val cheekLeft = blendshapes["cheekSquintLeft"] ?: 0f
        val cheekRight = blendshapes["cheekSquintRight"] ?: 0f
        val stretchLeft = blendshapes["mouthStretchLeft"] ?: 0f
        val stretchRight = blendshapes["mouthStretchRight"] ?: 0f
        val upperUpLeft = blendshapes["mouthUpperUpLeft"] ?: 0f
        val upperUpRight = blendshapes["mouthUpperUpRight"] ?: 0f

        val strongerSmile = maxOf(smileLeft, smileRight)
        val avgSmile = (smileLeft + smileRight) / 2.0f
        val avgCheek = (cheekLeft + cheekRight) / 2.0f
        val maxStretch = maxOf(stretchLeft, stretchRight)
        val avgUpperUp = (upperUpLeft + upperUpRight) / 2.0f

        val compositeScore = maxOf(
            strongerSmile,
            avgSmile * 1.15f,
            strongerSmile * 0.80f + avgCheek * 0.25f,
            avgSmile * 0.70f + maxStretch * 0.40f,
            avgSmile * 0.70f + avgUpperUp * 0.40f
        )

        val effectiveThreshold = (config.smileThreshold * 0.48f).coerceIn(0.14f, 0.28f)
        val requiredHold = 80L

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
     * Open mouth detection with jawOpen dominance, lip separation support, and 100ms hold time.
     * Supports natural, slight mouth opening.
     */
    private fun evaluateOpenMouth(blendshapes: Map<String, Float>, timestampMs: Long): Pair<Boolean, Float> {
        val jawOpen = blendshapes["jawOpen"] ?: 0f
        val lowerDownL = blendshapes["mouthLowerDownLeft"] ?: 0f
        val lowerDownR = blendshapes["mouthLowerDownRight"] ?: 0f
        val upperUpL = blendshapes["mouthUpperUpLeft"] ?: 0f
        val upperUpR = blendshapes["mouthUpperUpRight"] ?: 0f

        val avgLowerDown = (lowerDownL + lowerDownR) / 2.0f
        val avgUpperUp = (upperUpL + upperUpR) / 2.0f
        val lipSeparation = avgLowerDown * 0.60f + avgUpperUp * 0.40f

        // Robust composite score combining jaw movement with lip opening
        val compositeScore = maxOf(
            jawOpen,
            jawOpen * 0.70f + lipSeparation * 0.50f,
            lipSeparation * 0.90f
        )
        val effectiveThreshold = (config.mouthOpenThreshold * 0.65f).coerceIn(0.16f, 0.30f)
        val requiredHold = 100L

        if (compositeScore >= effectiveThreshold) {
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
            val progress = (compositeScore / effectiveThreshold) * 0.5f
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
        val targetYaw = (config.headYawThresholdDegrees * 0.68f).coerceIn(7.0f, 11.0f)
        val requiredHold = 80L

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
     * Head nod detection relative to starting posture and baseline pitch.
     * Supports bidirectional nod (nodding down-then-up OR up-then-down).
     * Excursion of >= 5° followed by return to within 4° of starting pitch.
     */
    private fun evaluateHeadNod(headPose: HeadPose, timestampMs: Long): Pair<Boolean, Float> {
        if (nodStartPitch == null) {
            nodStartPitch = if (abs(headPose.pitch - baselinePitch) <= 7.0f) headPose.pitch else baselinePitch
        }
        val refPitch = nodStartPitch ?: baselinePitch
        val deltaPitch = headPose.pitch - refPitch
        val absDelta = abs(deltaPitch)
        val targetExcursion = (config.headPitchThresholdDegrees * 0.65f).coerceIn(4.5f, 8.0f)
        val returnTolerance = 4.0f

        return when (nodPhase) {
            NodPhase.WAITING_FOR_EXCURSION -> {
                if (absDelta > nodPeakPitch) {
                    nodPeakPitch = absDelta
                }

                if (absDelta >= targetExcursion) {
                    nodPhase = NodPhase.WAITING_FOR_RETURN
                    nodDirection = if (deltaPitch >= 0f) NodDirection.DOWN else NodDirection.UP
                    nodPeakPitch = absDelta
                    nodStartMs = timestampMs
                    Pair(false, 0.65f)
                } else {
                    val progress = (nodPeakPitch / targetExcursion).coerceIn(0f, 0.6f)
                    Pair(false, progress)
                }
            }

            NodPhase.WAITING_FOR_RETURN -> {
                val elapsedSincePeak = timestampMs - nodStartMs
                if (absDelta > nodPeakPitch) {
                    nodPeakPitch = absDelta
                }

                if (elapsedSincePeak > 3000L) {
                    // Timed out waiting for return, restart excursion search from current pitch
                    nodPhase = NodPhase.WAITING_FOR_EXCURSION
                    nodDirection = NodDirection.NONE
                    nodPeakPitch = 0f
                    nodStartPitch = headPose.pitch
                    Pair(false, 0f)
                } else {
                    val isReturned = absDelta <= returnTolerance || absDelta <= (nodPeakPitch * 0.45f)

                    if (isReturned) {
                        nodPhase = NodPhase.COMPLETE
                        Pair(true, 1.0f)
                    } else {
                        val returnRatio = (1f - (absDelta / nodPeakPitch).coerceIn(0f, 1f))
                        val returnProgress = (0.65f + returnRatio * 0.35f).coerceIn(0.65f, 0.99f)
                        Pair(false, returnProgress)
                    }
                }
            }

            NodPhase.COMPLETE -> Pair(true, 1.0f)
        }
    }

    fun getDebugStatus(): String {
        return "Challenge: ${currentChallenge?.name ?: "None"}, Nod: $nodPhase [$nodDirection] (peak: ${"%.1f".format(nodPeakPitch)}°), Baseline: P=${"%.1f".format(baselinePitch)}° Y=${"%.1f".format(baselineYaw)}°"
    }
}
