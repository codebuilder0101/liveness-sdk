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
    public var enableDebugLogging: Bool

    public init(
        challenges: [LivenessChallenge] = [.blink, .smile],
        randomChallengeCount: Int = 2,
        challengeTimeoutSeconds: Float = 8.0,
        passiveSpoofThreshold: Float = 0.75,
        passiveFrameSampleSize: Int = 8,
        enableScreenSecurity: Bool = true,
        eyeBlinkThreshold: Float = 0.50,
        smileThreshold: Float = 0.35,
        mouthOpenThreshold: Float = 0.40,
        headYawThresholdDegrees: Float = 12.0,
        headPitchThresholdDegrees: Float = 10.0,
        enableDebugLogging: Bool = true
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
        self.enableDebugLogging = enableDebugLogging
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
