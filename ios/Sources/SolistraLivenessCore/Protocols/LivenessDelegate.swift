import Foundation

public protocol LivenessDelegate: AnyObject {
    func livenessSession(didUpdateState state: LivenessState)
    func livenessSession(didUpdateProgress progress: Float, forChallenge challenge: LivenessChallenge)
    func livenessSession(didCompleteWithResult result: LivenessResult)
    func livenessSession(didFailWithError error: LivenessError)
}
