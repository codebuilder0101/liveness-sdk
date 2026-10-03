package com.solistra.liveness.core

sealed class LivenessException(message: String, val errorCode: String) : Exception(message) {
    class CameraInitializationFailed(msg: String) : LivenessException(msg, "CAMERA_INIT_ERROR")
    class ModelLoadFailed(msg: String) : LivenessException(msg, "MODEL_LOAD_ERROR")
    class ChallengeTimeout(challenge: LivenessChallenge) : LivenessException("Challenge ${challenge.name} timed out", "TIMEOUT")
    class FaceLostDuringChallenge : LivenessException("Face was lost or moved outside the detection boundary", "FACE_LOST")
    class MultipleFacesDetected : LivenessException("Multiple faces detected in frame", "MULTIPLE_FACES")
    class PassiveAntiSpoofFailed(score: Float) : LivenessException("Passive anti-spoof verification failed (score: $score)", "SPOOF_DETECTED")
    class UserCancelled : LivenessException("Liveness verification cancelled by user", "USER_CANCELLED")
    class LowLightingCondition : LivenessException("Lighting condition too low for reliable verification", "LOW_LIGHT")
}
