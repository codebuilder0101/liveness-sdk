import Foundation

public enum LivenessError: LocalizedError, Equatable {
    case cameraInitializationFailed(String)
    case modelLoadFailed(String)
    case challengeTimeout(LivenessChallenge)
    case faceLost
    case multipleFacesDetected
    case passiveAntiSpoofFailed(score: Float)
    case userCancelled
    case screenCaptureDetected

    public var errorDescription: String? {
        switch self {
        case .cameraInitializationFailed(let msg): return "Camera initialization error: \(msg)"
        case .modelLoadFailed(let msg): return "Model initialization error: \(msg)"
        case .challengeTimeout(let ch): return "Challenge timed out: \(ch.promptText)"
        case .faceLost: return "Face moved outside boundary"
        case .multipleFacesDetected: return "Multiple faces detected in frame"
        case .passiveAntiSpoofFailed(let score): return "Anti-spoof check failed (score: \(score))"
        case .userCancelled: return "Verification cancelled by user"
        case .screenCaptureDetected: return "Screen recording/mirroring is not permitted"
        }
    }
}
