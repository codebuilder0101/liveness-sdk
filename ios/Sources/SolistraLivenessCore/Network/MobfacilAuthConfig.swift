import Foundation

public struct MobfacilAuthConfig: Equatable {
    public var baseUrl: String
    public var tokenEndpoint: String
    public var dataEndpoint: String
    public var clientId: String
    public var clientSecret: String
    public var grantType: String
    public var scope: String?
    public var renewalBufferSeconds: TimeInterval
    public var timeoutInterval: TimeInterval
    public var autoUploadOnSuccess: Bool
    public var enableDnsFallback: Bool
    public var fallbackIp: String

    public init(
        baseUrl: String = "https://api.stg.mobfacil.com.br",
        tokenEndpoint: String = "/login/token",
        dataEndpoint: String = "/collector/liveness",
        clientId: String = "sdk_ken_QA",
        clientSecret: String = "nkO2atoPD3po2U3vPYQz8m8V1LXq84fU",
        grantType: String = "client_credentials",
        scope: String? = nil,
        renewalBufferSeconds: TimeInterval = 60.0,
        timeoutInterval: TimeInterval = 30.0,
        autoUploadOnSuccess: Bool = false,
        enableDnsFallback: Bool = true,
        fallbackIp: String = "65.21.200.181"
    ) {
        self.baseUrl = baseUrl
        self.tokenEndpoint = tokenEndpoint
        self.dataEndpoint = dataEndpoint
        self.clientId = clientId
        self.clientSecret = clientSecret
        self.grantType = grantType
        self.scope = scope
        self.renewalBufferSeconds = renewalBufferSeconds
        self.timeoutInterval = timeoutInterval
        self.autoUploadOnSuccess = autoUploadOnSuccess
        self.enableDnsFallback = enableDnsFallback
        self.fallbackIp = fallbackIp
    }

    public var tokenUrl: URL {
        let base = baseUrl.hasSuffix("/") ? String(baseUrl.dropLast()) : baseUrl
        let endpoint = tokenEndpoint.hasPrefix("/") ? tokenEndpoint : "/\(tokenEndpoint)"
        return URL(string: base + endpoint)!
    }

    public var dataUrl: URL {
        let base = baseUrl.hasSuffix("/") ? String(baseUrl.dropLast()) : baseUrl
        let endpoint = dataEndpoint.hasPrefix("/") ? dataEndpoint : "/\(dataEndpoint)"
        return URL(string: base + endpoint)!
    }

    public static var staging: MobfacilAuthConfig {
        return MobfacilAuthConfig()
    }
}

