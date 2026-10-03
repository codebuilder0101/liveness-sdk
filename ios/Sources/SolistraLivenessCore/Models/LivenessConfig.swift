import Foundation

public struct LivenessConfig {
    public var challenges: [LivenessChallenge]
    public var randomChallengeCount: Int
    public var challengeTimeoutSeconds: Float
    public var passiveSpoofThreshold: Float
    public var passiveFrameSampleSize: Int
    public var enableScreenSecurity: Bool

    // Blendshape & Pose Thresholds
    public var eyeBlinkThreshold: Float
    public var smileThreshold: Float
    public var mouthOpenThreshold: Float
    public var headYawThresholdDegrees: Float
    public var headPitchThresholdDegrees: Float

    public init(
        challenges: [LivenessChallenge] = [.blink, .smile],
        randomChallengeCount: Int = 2,
        challengeTimeoutSeconds: Float = 4.0,
        passiveSpoofThreshold: Float = 0.85,
        passiveFrameSampleSize: Int = 8,
        enableScreenSecurity: Bool = true,
        eyeBlinkThreshold: Float = 0.60,
        smileThreshold: Float = 0.50,
        mouthOpenThreshold: Float = 0.55,
        headYawThresholdDegrees: Float = 20.0,
        headPitchThresholdDegrees: Float = 15.0
    ) {
        self.challenges = challenges
        self.randomChallengeCount = randomChallengeCount
        self.challengeTimeoutSeconds = challengeTimeoutSeconds
        self.passiveSpoofThreshold = passiveSpoofThreshold
        self.passiveFrameSampleSize = passiveFrameSampleSize
        self.enableScreenSecurity = enableScreenSecurity
        self.eyeBlinkThreshold = eyeBlinkThreshold
        self.smileThreshold = smileThreshold
        self.mouthOpenThreshold = mouthOpenThreshold
        self.headYawThresholdDegrees = headYawThresholdDegrees
        self.headPitchThresholdDegrees = headPitchThresholdDegrees
    }

    public static var `default`: LivenessConfig {
        return LivenessConfig()
    }

    public static var highSecurity: LivenessConfig {
        return LivenessConfig(
            randomChallengeCount: 3,
            challengeTimeoutSeconds: 3.5,
            passiveSpoofThreshold: 0.90,
            passiveFrameSampleSize: 12
        )
    }
}
