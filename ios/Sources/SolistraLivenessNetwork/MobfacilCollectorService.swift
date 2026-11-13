import Foundation
import UIKit
import SolistraLivenessCore

/**
 * Service protocol for uploading liveness verification results to upstream collector.
 */
public protocol LivenessCollectorServiceProtocol {
    func uploadLivenessData(
        result: LivenessResult,
        customMetadata: [String: String]
    ) async -> MobfacilUploadResult
}

/**
 * Implementation of MobFácil Flow 2: Telemetry & Snapshot Ingestion to KrakenD API Gateway.
 *
 * Implements:
 * - JPEG Base64 encoding of face image
 * - Bearer authorization header injection
 * - Automatic 401 retry with token renewal (Flow 2 Alt)
 */
public final class MobfacilCollectorService: LivenessCollectorServiceProtocol {

    private let tokenManager: MobfacilTokenManager
    public let config: MobfacilAuthConfig
    private let session: URLSession

    public init(
        tokenManager: MobfacilTokenManager = .shared,
        config: MobfacilAuthConfig = .staging,
        session: URLSession? = nil
    ) {
        self.tokenManager = tokenManager
        self.config = config
        self.session = session ?? URLSession(configuration: .default)
    }

    public func uploadLivenessData(
        result: LivenessResult,
        customMetadata: [String: String] = [:]
    ) async -> MobfacilUploadResult {
        let payload = buildPayload(result: result, customMetadata: customMetadata)

        guard let jsonData = try? JSONEncoder().encode(payload) else {
            return .failure(
                statusCode: nil,
                message: "Failed to encode liveness payload to JSON",
                errorCode: "JSON_ENCODING_FAILED",
                isRetriable: false
            )
        }

        // Attempt 1: Fetch valid or cached token
        let token: String
        do {
            token = try await tokenManager.getAccessToken(forceRefresh: false)
        } catch {
            return .failure(
                statusCode: nil,
                message: "Authentication failed before upload: \(error.localizedDescription)",
                errorCode: "AUTH_PRE_UPLOAD_FAILED",
                isRetriable: false
            )
        }

        do {
            let firstResponse = try await executePost(url: config.dataUrl, token: token, body: jsonData)

            // Flow 2 Alt: 401 Unauthorized received, renew token and retry once
            if firstResponse.statusCode == 401 {
                let freshToken = try await tokenManager.getAccessToken(forceRefresh: true)
                let retryResponse = try await executePost(url: config.dataUrl, token: freshToken, body: jsonData)

                if (200...299).contains(retryResponse.statusCode) {
                    return .success(statusCode: retryResponse.statusCode, responseBody: retryResponse.body)
                } else {
                    return .failure(
                        statusCode: retryResponse.statusCode,
                        message: retryResponse.body,
                        errorCode: "HTTP_\(retryResponse.statusCode)",
                        isRetriable: (500...599).contains(retryResponse.statusCode)
                    )
                }
            }

            if (200...299).contains(firstResponse.statusCode) {
                return .success(statusCode: firstResponse.statusCode, responseBody: firstResponse.body)
            } else {
                return .failure(
                    statusCode: firstResponse.statusCode,
                    message: firstResponse.body,
                    errorCode: "HTTP_\(firstResponse.statusCode)",
                    isRetriable: (500...599).contains(firstResponse.statusCode)
                )
            }
        } catch {
            return .failure(
                statusCode: nil,
                message: error.localizedDescription,
                errorCode: "NETWORK_ERROR",
                isRetriable: true
            )
        }
    }

    private func executePost(url: URL, token: String, body: Data) async throws -> (statusCode: Int, body: String) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = config.timeoutInterval
        request.httpBody = body

        let (data, response) = try await session.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        let bodyString = String(data: data, encoding: .utf8) ?? ""
        return (statusCode, bodyString)
    }

    private func buildPayload(result: LivenessResult, customMetadata: [String: String]) -> MobfacilLivenessPayload {
        var base64Face: String? = nil
        if let image = result.bestFaceImage,
           let jpegData = image.jpegData(compressionQuality: 0.85) {
            base64Face = jpegData.base64EncodedString()
        }

        var metadata: [String: String] = [
            "platform": "iOS",
            "sdk_name": "SolistraLivenessSDK",
            "sdk_version": "1.0.0",
            "os_version": UIDevice.current.systemVersion,
            "device_model": UIDevice.current.model
        ]
        metadata.merge(customMetadata) { _, new in new }

        return MobfacilLivenessPayload(
            isLive: result.isLive,
            passiveScore: result.passiveScore,
            completedChallenges: result.completedChallenges.map { $0.promptText },
            sessionDurationMs: result.sessionDurationMs,
            faceImageBase64: base64Face,
            timestamp: result.timestamp.timeIntervalSince1970,
            metadata: metadata
        )
    }
}

