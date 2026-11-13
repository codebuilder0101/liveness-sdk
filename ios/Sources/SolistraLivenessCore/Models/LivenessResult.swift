import Foundation
import UIKit

public struct LivenessResult: Equatable {
    public let isLive: Bool
    public let passiveScore: Float
    public let completedChallenges: [LivenessChallenge]
    public let bestFaceImage: UIImage?
    public let sessionDurationMs: Double
    public let timestamp: Date
    public let uploadResult: MobfacilUploadResult?

    public init(
        isLive: Bool,
        passiveScore: Float,
        completedChallenges: [LivenessChallenge],
        bestFaceImage: UIImage?,
        sessionDurationMs: Double,
        timestamp: Date = Date(),
        uploadResult: MobfacilUploadResult? = nil
    ) {
        self.isLive = isLive
        self.passiveScore = passiveScore
        self.completedChallenges = completedChallenges
        self.bestFaceImage = bestFaceImage
        self.sessionDurationMs = sessionDurationMs
        self.timestamp = timestamp
        self.uploadResult = uploadResult
    }
}
