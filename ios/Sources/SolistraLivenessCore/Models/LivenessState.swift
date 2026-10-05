import Foundation

public enum LivenessState: Equatable {
    case idle
    case initializing
    case faceAlignment(message: String, isFaceDetected: BooleanLiteralType, isCentered: Bool, isAppropriateDistance: Bool)
    case performingChallenge(challenge: LivenessChallenge, challengeIndex: Int, totalChallenges: Int, progress: Float, remainingSeconds: Float)
    case interChallenge(message: String, completedChallenge: LivenessChallenge, nextChallenge: LivenessChallenge, completedIndex: Int, totalChallenges: Int)
    case evaluatingPassive(progress: Float)
    case success(result: LivenessResult)
    case failed(error: LivenessError)
}
