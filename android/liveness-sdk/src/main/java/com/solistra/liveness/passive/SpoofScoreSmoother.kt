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
        return scores.sum() / scores.size
    }

    @Synchronized
    fun isReliablyReal(): Boolean {
        if (scores.isEmpty()) return false
        val avg = getAverageScore()
        val lowScoreCount = scores.count { it < 0.35f }
        // Verified live if average confidence meets threshold and not dominated by spoof frames
        return avg >= passThreshold && lowScoreCount <= (scores.size / 3)
    }

    @Synchronized
    fun getSampleCount(): Int = scores.size

    @Synchronized
    fun reset() {
        scores.clear()
    }
}
