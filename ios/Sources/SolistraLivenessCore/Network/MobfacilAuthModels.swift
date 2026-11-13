import Foundation

/**
 * Immutable OAuth2 access token issued by Keycloak through KrakenD.
 */
public struct MobfacilToken: Codable, Equatable {
    public let accessToken: String
    public let tokenType: String
    public let expiresIn: Int
    public let issuedAt: Date
    public let scope: String?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case tokenType = "token_type"
        case expiresIn = "expires_in"
        case scope = "scope"
    }

    public init(
        accessToken: String,
        tokenType: String = "Bearer",
        expiresIn: Int = 300,
        issuedAt: Date = Date(),
        scope: String? = nil
    ) {
        self.accessToken = accessToken
        self.tokenType = tokenType
        self.expiresIn = expiresIn
        self.issuedAt = issuedAt
        self.scope = scope
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.accessToken = try container.decode(String.self, forKey: .accessToken)
        self.tokenType = try container.decodeIfPresent(String.self, forKey: .tokenType) ?? "Bearer"
        self.expiresIn = try container.decodeIfPresent(Int.self, forKey: .expiresIn) ?? 300
        self.scope = try container.decodeIfPresent(String.self, forKey: .scope)
        self.issuedAt = Date()
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(accessToken, forKey: .accessToken)
        try container.encode(tokenType, forKey: .tokenType)
        try container.encode(expiresIn, forKey: .expiresIn)
        try container.encodeIfPresent(scope, forKey: .scope)
    }

    public var isExpired: Bool {
        return Date().timeIntervalSince(issuedAt) >= Double(expiresIn)
    }

    public func isExpiringSoon(buffer: TimeInterval = 60.0) -> Bool {
        return Date().timeIntervalSince(issuedAt) >= (Double(expiresIn) - buffer)
    }

    public var remainingSeconds: TimeInterval {
        return max(0, Double(expiresIn) - Date().timeIntervalSince(issuedAt))
    }

    public var authorizationHeader: String {
        return "\(tokenType) \(accessToken)"
    }
}

/**
 * Error states for MobFácil Keycloak & KrakenD flows.
 */
public enum MobfacilAuthError: LocalizedError, Equatable {
    /// Flow 4: Inadimplência (Keycloak disabled client account)
    case licenseBlocked(String)
    /// Flow 5: Rotated or Compromised Secret
    case invalidCredentials(String)
    /// HTTP Error with status code
    case httpError(statusCode: Int, message: String)
    /// Network connection error
    case networkError(String)
    /// JSON parsing error
    case decodingError(String)

    public var errorDescription: String? {
        switch self {
        case .licenseBlocked(let msg):
            return "Licença bloqueada por inadimplência: \(msg)"
        case .invalidCredentials(let msg):
            return "Credenciais inválidas ou secret rotacionado: \(msg)"
        case .httpError(let code, let msg):
            return "Erro HTTP \(code): \(msg)"
        case .networkError(let msg):
            return "Erro de conexão: \(msg)"
        case .decodingError(let msg):
            return "Erro ao processar resposta: \(msg)"
        }
    }
}

/**
 * Result of liveness verification telemetry transmission (Flow 2).
 */
public enum MobfacilUploadResult: Equatable {
    case success(statusCode: Int, responseBody: String)
    case failure(statusCode: Int?, message: String, errorCode: String, isRetriable: Bool)
}

/**
 * Payload sent to KrakenD collector endpoint.
 */
public struct MobfacilLivenessPayload: Codable {
    public let is_live: Bool
    public let passive_score: Float
    public let completed_challenges: [String]
    public let session_duration_ms: Double
    public let timestamp: TimeInterval
    public let face_image_base64: String?
    public let metadata: [String: String]

    public init(
        isLive: Bool,
        passiveScore: Float,
        completedChallenges: [String],
        sessionDurationMs: Double,
        faceImageBase64: String?,
        timestamp: TimeInterval = Date().timeIntervalSince1970,
        metadata: [String: String] = [:]
    ) {
        self.is_live = isLive
        self.passive_score = passiveScore
        self.completed_challenges = completedChallenges
        self.session_duration_ms = sessionDurationMs
        self.face_image_base64 = faceImageBase64
        self.timestamp = timestamp
        self.metadata = metadata
    }
}

