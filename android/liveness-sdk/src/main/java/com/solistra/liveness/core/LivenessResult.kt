package com.solistra.liveness.core

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result payload containing verification outcome, best face snapshot, and security metrics.
 */
@Parcelize
data class LivenessResult(
    val isLive: Boolean,
    val passiveScore: Float,
    val completedChallenges: List<LivenessChallenge>,
    val bestFaceImage: Bitmap?,
    val sessionDurationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable
