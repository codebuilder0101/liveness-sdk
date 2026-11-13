package com.solistra.liveness.network

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration options for MobFácil Keycloak OAuth2 authentication and KrakenD Gateway integration.
 */
@Parcelize
data class MobfacilAuthConfig(
    /** Base URL of the KrakenD API Gateway. Default: staging */
    val baseUrl: String = DEFAULT_BASE_URL,

    /** OAuth2 token endpoint on the gateway. Default: /login/token */
    val tokenEndpoint: String = DEFAULT_TOKEN_ENDPOINT,

    /** Liveness / document collector endpoint. Default: /collector/liveness */
    val dataEndpoint: String = DEFAULT_DATA_ENDPOINT,

    /** Keycloak registered client identifier */
    val clientId: String = DEFAULT_CLIENT_ID,

    /** Keycloak confidential client secret */
    val clientSecret: String = DEFAULT_CLIENT_SECRET,

    /** OAuth2 grant type. Always client_credentials for server-to-server / SDK */
    val grantType: String = GRANT_TYPE_CLIENT_CREDENTIALS,

    /** Requested OAuth2 scope (optional) */
    val scope: String? = null,

    /**
     * Safety buffer before expiration (in seconds) to trigger proactive renewal (Flow 3).
     * Default: 60 seconds before 300s token expiry.
     */
    val renewalBufferSeconds: Long = 60L,

    /** Connect timeout for network requests in seconds */
    val connectTimeoutSeconds: Long = 15L,

    /** Read timeout for network requests in seconds */
    val readTimeoutSeconds: Long = 30L,

    /** Write timeout for network requests in seconds */
    val writeTimeoutSeconds: Long = 30L,

    /**
     * Whether to automatically upload liveness verification result upon successful on-device verification.
     */
    val autoUploadOnSuccess: Boolean = false,

    /**
     * Whether to use custom MobfacilDns fallback for international networks where
     * Brazilian .com.br CNAMEs fail to resolve.
     */
    val enableDnsFallback: Boolean = true
) : Parcelable {

    fun getFullTokenUrl(): String = baseUrl.trimEnd('/') + "/" + tokenEndpoint.trimStart('/')
    fun getFullDataUrl(): String = baseUrl.trimEnd('/') + "/" + dataEndpoint.trimStart('/')

    companion object {
        const val DEFAULT_BASE_URL = "https://api.stg.mobfacil.com.br"
        const val DEFAULT_TOKEN_ENDPOINT = "/login/token"
        const val DEFAULT_DATA_ENDPOINT = "/collector/liveness"
        const val DEFAULT_CLIENT_ID = "sdk_ken_QA"
        const val DEFAULT_CLIENT_SECRET = "nkO2atoPD3po2U3vPYQz8m8V1LXq84fU"
        const val GRANT_TYPE_CLIENT_CREDENTIALS = "client_credentials"

        fun staging(): MobfacilAuthConfig = MobfacilAuthConfig()

        fun production(baseUrl: String, clientId: String, clientSecret: String): MobfacilAuthConfig =
            MobfacilAuthConfig(
                baseUrl = baseUrl,
                clientId = clientId,
                clientSecret = clientSecret
            )
    }
}

