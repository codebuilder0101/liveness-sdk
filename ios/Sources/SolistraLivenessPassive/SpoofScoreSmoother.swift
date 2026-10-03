import Foundation

public final class SpoofScoreSmoother {
    private let windowSize: Int
    private let passThreshold: Float
    private var scores: [Float] = []
    private let lock = NSLock()

    public init(windowSize: Int = 8, passThreshold: Float = 0.85) {
        self.windowSize = windowSize
        self.passThreshold = passThreshold
    }

    public func addSample(realConfidence: Float) {
        lock.lock()
        defer { lock.unlock() }

        if scores.count >= windowSize {
            scores.removeFirst()
        }
        scores.append(realConfidence)
    }

    public func getAverageScore() -> Float {
        lock.lock()
        defer { lock.unlock() }

        guard !scores.isEmpty else { return 0 }
        return scores.reduce(0, +) / Float(scores.count)
    }

    public func isReliablyReal() -> Bool {
        lock.lock()
        defer { lock.unlock() }

        guard scores.count >= windowSize / 2 else { return false }
        let avg = scores.reduce(0, +) / Float(scores.count)
        let hasDrop = scores.contains { $0 < 0.20 }
        return avg >= passThreshold && !hasDrop
    }

    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        scores.removeAll()
    }
}
