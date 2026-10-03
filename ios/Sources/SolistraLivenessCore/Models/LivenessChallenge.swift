import Foundation

public enum LivenessChallenge: String, CaseIterable, Codable {
    case blink = "BLINK"
    case smile = "SMILE"
    case turnLeft = "TURN_LEFT"
    case turnRight = "TURN_RIGHT"
    case nodHead = "NOD_HEAD"
    case openMouth = "OPEN_MOUTH"

    public var promptText: String {
        switch self {
        case .blink: return "Blink your eyes"
        case .smile: return "Smile naturally"
        case .turnLeft: return "Turn your head slowly to the left"
        case .turnRight: return "Turn your head slowly to the right"
        case .nodHead: return "Nod your head up and down"
        case .openMouth: return "Open your mouth slightly"
        }
    }
}
