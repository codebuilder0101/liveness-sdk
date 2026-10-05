package com.solistra.liveness.passive

import java.util.ArrayDeque

/**
 * Temporal smoothing and sliding window aggregator for passive spoof scores.
 */
class SpoofScoreSmoother(
    private val windowSize: Int = 8,
    private val passThreshold: Float = 0.75f
) {
    private val scores = ArrayDeque<Float>(windowSize)

    @Synchronized
    fun addSample(realConfidence: Float) {
        if (scores.size >= windowSize) {
            scores.removeFirst()
        }
        scores.addLast(realConfidence)
    }

    @Synchronized
    fun getAverageScore(): Float {
        if (scores.isEmpty()) return 0f
        // Discard lowest 25% outliers caused by transient motion blur/deformation during active gestures
        val sorted = scores.sorted()
        val keepCount = maxOf(1, (sorted.size * 0.75f).toInt())
        val topScores = sorted.takeLast(keepCount)
        return topScores.sum() / topScores.size
    }

    @Synchronized
    fun isReliablyReal(): Boolean {
        if (scores.isEmpty()) return true
        val avg = getAverageScore()
        val lowScoreCount = scores.count { it < 0.25f }
        return avg >= passThreshold && lowScoreCount <= maxOf(1, scores.size / 3)
    }

    @Synchronized
    fun getSampleCount(): Int = scores.size

    @Synchronized
    fun reset() {
        scores.clear()
    }
}
