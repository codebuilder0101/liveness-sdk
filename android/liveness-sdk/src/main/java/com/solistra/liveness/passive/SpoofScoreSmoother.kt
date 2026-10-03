package com.solistra.liveness.passive

import java.util.ArrayDeque

/**
 * Temporal smoothing and sliding window aggregator for passive spoof scores.
 */
class SpoofScoreSmoother(
    private val windowSize: Int = 8,
    private val passThreshold: Float = 0.85f
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
        if (scores.size < windowSize / 2) return false
        val avg = getAverageScore()
        val hasCatastrophicDrop = scores.any { it < 0.20f }
        return avg >= passThreshold && !hasCatastrophicDrop
    }

    @Synchronized
    fun getSampleCount(): Int = scores.size

    @Synchronized
    fun reset() {
        scores.clear()
    }
}
