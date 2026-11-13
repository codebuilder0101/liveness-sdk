import XCTest
@testable import SolistraLivenessCore
@testable import SolistraLivenessNetwork

final class MobfacilNetworkTests: XCTestCase {

    func testTokenExpirationMath() {
        let now = Date()
        let tokenFresh = MobfacilToken(
            accessToken: "test_fresh",
            tokenType: "Bearer",
            expiresIn: 300,
            issuedAt: now
        )
        XCTAssertFalse(tokenFresh.isExpired)
        XCTAssertFalse(tokenFresh.isExpiringSoon(buffer: 60.0))
        XCTAssertEqual(tokenFresh.authorizationHeader, "Bearer test_fresh")

        let tokenExpiringSoon = MobfacilToken(
            accessToken: "test_expiring",
            tokenType: "Bearer",
            expiresIn: 300,
            issuedAt: now.addingTimeInterval(-245) // 55s remaining <= 60s buffer
        )
        XCTAssertFalse(tokenExpiringSoon.isExpired)
        XCTAssertTrue(tokenExpiringSoon.isExpiringSoon(buffer: 60.0))

        let tokenExpired = MobfacilToken(
            accessToken: "test_expired",
            tokenType: "Bearer",
            expiresIn: 300,
            issuedAt: now.addingTimeInterval(-301)
        )
        XCTAssertTrue(tokenExpired.isExpired)
        XCTAssertTrue(tokenExpired.isExpiringSoon(buffer: 60.0))
    }

    func testAuthConfigUrls() {
        let config = MobfacilAuthConfig.staging
        XCTAssertEqual(config.tokenUrl.absoluteString, "https://api.stg.mobfacil.com.br/login/token")
        XCTAssertEqual(config.dataUrl.absoluteString, "https://api.stg.mobfacil.com.br/collector/liveness")
        XCTAssertEqual(config.clientId, "sdk_ken_QA")
        XCTAssertEqual(config.clientSecret, "nkO2atoPD3po2U3vPYQz8m8V1LXq84fU")
        XCTAssertEqual(config.renewalBufferSeconds, 60.0)
    }

    func testPayloadSerialization() throws {
        let payload = MobfacilLivenessPayload(
            isLive: true,
            passiveScore: 0.95,
            completedChallenges: ["Blink", "Smile"],
            sessionDurationMs: 2500.0,
            faceImageBase64: "dGVzdF9iYXNlNjQ=",
            timestamp: 1700000000.0,
            metadata: ["platform": "iOS", "os_version": "17.0"]
        )

        let data = try JSONEncoder().encode(payload)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertNotNil(json)
        XCTAssertEqual(json?["is_live"] as? Bool, true)
        XCTAssertEqual((json?["completed_challenges"] as? [String])?.count, 2)
        XCTAssertEqual(json?["face_image_base64"] as? String, "dGVzdF9iYXNlNjQ=")
    }

    func testTokenManagerFlow1Success() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let mockTokenJson = """
        {
            "access_token": "mock_jwt_swift_token",
            "token_type": "Bearer",
            "expires_in": 300,
            "scope": "email profile"
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/login/token")
            XCTAssertEqual(request.httpMethod, "POST")
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, mockTokenJson)
        }

        let config = MobfacilAuthConfig.staging
        let manager = MobfacilTokenManager(config: config, session: session)
        let token = try await manager.getAccessToken(forceRefresh: true)

        XCTAssertEqual(token, "mock_jwt_swift_token")
    }

    func testTokenManagerFlow4Inadimplencia() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let mockDelinquencyJson = """
        {
            "error": "invalid_client",
            "error_description": "Invalid client or Invalid client credentials"
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 401,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, mockDelinquencyJson)
        }

        let manager = MobfacilTokenManager(config: .staging, session: session)

        do {
            _ = try await manager.getAccessToken(forceRefresh: true)
            XCTFail("Expected licenseBlocked error")
        } catch let error as MobfacilAuthError {
            switch error {
            case .licenseBlocked(let msg):
                XCTAssertTrue(msg.contains("Invalid client") || msg.contains("inadimplência"))
            default:
                XCTFail("Unexpected error type: \(error)")
            }
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testTokenManagerFlow5RotatedSecret() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let mockRotatedSecretJson = """
        {
            "error": "unauthorized_client",
            "error_description": "Invalid client or Invalid client credentials"
        }
        """.data(using: .utf8)!

        MockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 401,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, mockRotatedSecretJson)
        }

        let manager = MobfacilTokenManager(config: .staging, session: session)

        do {
            _ = try await manager.getAccessToken(forceRefresh: true)
            XCTFail("Expected invalidCredentials error")
        } catch let error as MobfacilAuthError {
            switch error {
            case .invalidCredentials(let msg):
                XCTAssertTrue(msg.contains("Invalid client") || msg.contains("rotacionado"))
            default:
                XCTFail("Unexpected error type: \(error)")
            }
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }
}

// MARK: - MockURLProtocol for URLSession Testing
final class MockURLProtocol: URLProtocol {
    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        guard let handler = MockURLProtocol.requestHandler else {
            XCTFail("No handler configured for MockURLProtocol")
            return
        }

        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

