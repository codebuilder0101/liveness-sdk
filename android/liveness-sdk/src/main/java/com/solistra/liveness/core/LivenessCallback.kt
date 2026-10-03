package com.solistra.liveness.core

interface LivenessCallback {
    fun onStateChanged(state: LivenessState)
    fun onChallengeProgress(challenge: LivenessChallenge, progress: Float)
    fun onSuccess(result: LivenessResult)
    fun onError(error: LivenessException)
}
