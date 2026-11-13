package com.solistra.liveness.core

sealed class LivenessException(message: String, val errorCode: String) : Exception(message) {
    class CameraInitializationFailed(msg: String) : LivenessException(msg, "CAMERA_INIT_ERROR")
    class ModelLoadFailed(msg: String) : LivenessException(msg, "MODEL_LOAD_ERROR")
    class ChallengeTimeout(challenge: LivenessChallenge) : LivenessException("Challenge ${challenge.name} timed out", "TIMEOUT")
    class FaceLostDuringChallenge : LivenessException("Face was lost or moved outside the detection boundary", "FACE_LOST")
    class MultipleFacesDetected : LivenessException("Multiple faces detected in frame", "MULTIPLE_FACES")
    class PassiveAntiSpoofFailed(val score: Float) : LivenessException("Passive anti-spoof verification failed (score: $score)", "SPOOF_DETECTED")
    class UserCancelled : LivenessException("Liveness verification cancelled by user", "USER_CANCELLED")
    class LowLightingCondition : LivenessException("Lighting condition too low for reliable verification", "LOW_LIGHT")

    // MobFácil Auth and Transmission Errors
    class MobfacilDelinquencyLicenseBlocked(msg: String = "Licença bloqueada por inadimplência. Entre em contato com a MobFácil.") :
        LivenessException(msg, "LICENSE_BLOCKED")
    class MobfacilAuthenticationFailed(msg: String, errorCode: String = "AUTH_FAILED") :
        LivenessException(msg, errorCode)
    class MobfacilUploadFailed(msg: String, val statusCode: Int? = null) :
        LivenessException(msg, "UPLOAD_FAILED")
}
