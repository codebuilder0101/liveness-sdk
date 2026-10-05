package com.solistra.liveness.core

/**
 * State machine states for the liveness session.
 */
sealed class LivenessState {
    object Idle : LivenessState()
    object Initializing : LivenessState()
    
    data class FaceAlignment(
        val message: String,
        val isFaceDetected: Boolean,
        val isCentered: Boolean,
        val isAppropriateDistance: Boolean
    ) : LivenessState()

    data class PerformingChallenge(
        val challenge: LivenessChallenge,
        val challengeIndex: Int,
        val totalChallenges: Int,
        val progress: Float, // 0.0 to 1.0
        val remainingSeconds: Float
    ) : LivenessState()

    data class InterChallenge(
        val message: String = "Look straight at the camera",
        val completedChallenge: LivenessChallenge,
        val nextChallenge: LivenessChallenge,
        val completedIndex: Int,
        val totalChallenges: Int
    ) : LivenessState()

    data class EvaluatingPassive(
        val progress: Float
    ) : LivenessState()

    data class Success(
        val result: LivenessResult
    ) : LivenessState()

    data class Failed(
        val error: LivenessException
    ) : LivenessState()
}
