import Foundation
import SolistraLivenessCore

/**
 * Thread-safe OAuth2 token manager for MobFácil Keycloak IDP through KrakenD Gateway.
 *
 * Implements:
 * - Flow 1: Company client_credentials authentication
 * - Flow 3: Proactive background renewal before token expiry (60s buffer)
 * - Flow 4: Inadimplência (Delinquency detection via invalid_client)
 * - Flow 5: Vazamento do Secret (Rotation/leak detection via unauthorized_client)
 * - Concurrency protection via Swift Actor isolation
 * - Persistence across app restarts via UserDefaults
 */
public actor MobfacilTokenManager {

    public static let shared = MobfacilTokenManager()

    private var cachedToken: MobfacilToken?
    public let config: MobfacilAuthConfig
    private let session: URLSession

    private let userDefaultsKey = "com.mobfacil.auth.cached_token"
    private let userDefaultsClientKey = "com.mobfacil.auth.client_id"

    public init(config: MobfacilAuthConfig = .staging, session: URLSession? = nil) {
        self.config = config
        self.session = session ?? URLSession(configuration: .default)
        self.loadCachedToken()
    }

    /**
     * Retrieves a valid Bearer access token string.
     * Proactively refreshes the token if it is within [MobfacilAuthConfig.renewalBufferSeconds] of expiration.
     */
    public func getAccessToken(forceRefresh: Bool = false) async throws -> String {
        if !forceRefresh, let token = cachedToken, !token.isExpiringSoon(buffer: config.renewalBufferSeconds) {
            return token.accessToken
        }

        return try await requestNewToken()
    }

    public func authenticate(forceRefresh: Bool = false) async throws -> MobfacilToken {
        if !forceRefresh, let token = cachedToken, !token.isExpiringSoon(buffer: config.renewalBufferSeconds) {
            return token
        }

        _ = try await requestNewToken()
        guard let validToken = cachedToken else {
            throw MobfacilAuthError.networkError("Token not available after authentication")
        }
        return validToken
    }

    private func requestNewToken() async throws -> String {
        var request = URLRequest(url: config.tokenUrl)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = config.timeoutInterval

        var params = [
            "grant_type": config.grantType,
            "client_id": config.clientId,
            "client_secret": config.clientSecret
        ]
        if let scope = config.scope, !scope.isEmpty {
            params["scope"] = scope
        }

        let bodyString = params
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")" }
            .joined(separator: "&")
        request.httpBody = bodyString.data(using: .utf8)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // Check for international DNS resolution failure and retry with fallback IP
            if config.enableDnsFallback {
                do {
                    (data, response) = try await executeDnsFallbackRequest(request: request)
                } catch {
                    throw MobfacilAuthError.networkError(error.localizedDescription)
                }
            } else {
                throw MobfacilAuthError.networkError(error.localizedDescription)
            }
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw MobfacilAuthError.networkError("Invalid HTTP server response")
        }

        if httpResponse.statusCode == 200 {
            do {
                let token = try JSONDecoder().decode(MobfacilToken.self, from: data)
                self.cachedToken = token
                self.saveToken(token)
                return token.accessToken
            } catch {
                throw MobfacilAuthError.decodingError("Failed to decode token JSON: \(error.localizedDescription)")
            }
        } else {
            throw parseErrorResponse(statusCode: httpResponse.statusCode, data: data)
        }
    }

    private func parseErrorResponse(statusCode: Int, data: Data) -> MobfacilAuthError {
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        let errorCode = json["error"] as? String ?? ""
        let errorDesc = json["error_description"] as? String ?? ""

        switch errorCode.lowercased() {
        case "invalid_client":
            // Flow 4: Inadimplência
            return .licenseBlocked(errorDesc.isEmpty ? "Licença bloqueada por inadimplência." : errorDesc)
        case "unauthorized_client":
            // Flow 5: Vazamento do Secret / Rotated Secret
            return .invalidCredentials(errorDesc.isEmpty ? "Credenciais inválidas ou secret rotacionado." : errorDesc)
        default:
            let message = errorDesc.isEmpty ? (json["message"] as? String ?? "HTTP \(statusCode)") : errorDesc
            return .httpError(statusCode: statusCode, message: message)
        }
    }

    public func invalidateCache() {
        cachedToken = nil
        UserDefaults.standard.removeObject(forKey: userDefaultsKey)
        UserDefaults.standard.removeObject(forKey: userDefaultsClientKey)
    }

    private func saveToken(_ token: MobfacilToken) {
        if let encoded = try? JSONEncoder().encode(token) {
            UserDefaults.standard.set(encoded, forKey: userDefaultsKey)
            UserDefaults.standard.set(config.clientId, forKey: userDefaultsClientKey)
        }
    }

    private func loadCachedToken() {
        guard let savedClientId = UserDefaults.standard.string(forKey: userDefaultsClientKey),
              savedClientId == config.clientId,
              let data = UserDefaults.standard.data(forKey: userDefaultsKey),
              let token = try? JSONDecoder().decode(MobfacilToken.self, from: data) else {
            return
        }

        if !token.isExpired {
            self.cachedToken = token
        } else {
            UserDefaults.standard.removeObject(forKey: userDefaultsKey)
        }
    }

    private func executeDnsFallbackRequest(request: URLRequest) async throws -> (Data, URLResponse) {
        guard let originalUrl = request.url,
              let host = originalUrl.host,
              host.contains("mobfacil.com.br") else {
            throw MobfacilAuthError.networkError("DNS fallback not applicable")
        }

        var fallbackComponents = URLComponents(url: originalUrl, resolvingAgainstBaseURL: false)
        fallbackComponents?.host = config.fallbackIp
        guard let fallbackUrl = fallbackComponents?.url else {
            throw MobfacilAuthError.networkError("Could not build fallback URL")
        }

        var fallbackRequest = request
        fallbackRequest.url = fallbackUrl
        fallbackRequest.setValue(host, forHTTPHeaderField: "Host")

        let delegate = MobfacilTlsPinningDelegate(expectedHost: host)
        let fallbackSession = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        return try await fallbackSession.data(for: fallbackRequest)
    }
}
