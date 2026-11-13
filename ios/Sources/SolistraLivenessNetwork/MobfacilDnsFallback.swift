import Foundation
import Security

/**
 * Resilient TLS session delegate that validates server certificates against the original
 * MobFácil hostname when connecting via the direct fallback IP address (65.21.200.181).
 */
public final class MobfacilTlsPinningDelegate: NSObject, URLSessionDelegate {

    private let expectedHost: String

    public init(expectedHost: String) {
        self.expectedHost = expectedHost
        super.init()
    }

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // Set the expected hostname policy so SecTrustEvaluate validates against api.stg.mobfacil.com.br
        let policy = SecPolicyCreateSSL(true, expectedHost as CFString)
        SecTrustSetPolicies(serverTrust, policy)

        var error: CFError?
        let isValid = SecTrustEvaluateWithError(serverTrust, &error)

        if isValid {
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}

