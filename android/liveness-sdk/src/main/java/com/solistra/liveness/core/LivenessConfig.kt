package com.solistra.liveness.core

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration options for the Liveness SDK session.
 */
@Parcelize
data class LivenessConfig(
    /**
     * List of active challenges to perform. If empty, a random sequence is chosen from default pool.
     */
    val challenges: List<LivenessChallenge> = listOf(
        LivenessChallenge.BLINK,
        LivenessChallenge.SMILE
    ),

    /**
     * Number of random challenges if dynamic randomized mode is used. Default: 2.
     */
    val randomChallengeCount: Int = 2,

    /**
     * Maximum time in seconds allocated for each individual challenge before timeout.
     */
    val challengeTimeoutSeconds: Float = 6.0f,

    /**
     * Required minimum confidence threshold for passive anti-spoofing (0.0 to 1.0).
     */
    val passiveSpoofThreshold: Float = 0.75f,

    /**
     * Number of sharp frames to accumulate for passive anti-spoofing averaging.
     */
    val passiveFrameSampleSize: Int = 8,

    /**
     * MediaPipe delegate execution mode: CPU or GPU.
     */
    val useGpuDelegate: Boolean = true,

    /**
     * Prevent screen recording, mirroring, and screenshot capture.
     */
    val enableScreenSecurity: Boolean = true,

    /**
     * Minimum frame luminance required (0.0 to 255.0). Default: 30.0f.
     */
    val minLuminanceThreshold: Float = 30.0f,

    /**
     * Thresholds for active blendshapes
     */
    val eyeBlinkThreshold: Float = 0.60f,
    val smileThreshold: Float = 0.35f,
    val mouthOpenThreshold: Float = 0.55f,
    val headYawThresholdDegrees: Float = 18.0f,
    val headPitchThresholdDegrees: Float = 18.0f
) : Parcelable {

    companion object {
        fun default(): LivenessConfig = LivenessConfig()

        fun highSecurity(): LivenessConfig = LivenessConfig(
            randomChallengeCount = 3,
            challengeTimeoutSeconds = 3.5f,
            passiveSpoofThreshold = 0.90f,
            passiveFrameSampleSize = 12
        )
    }
}
