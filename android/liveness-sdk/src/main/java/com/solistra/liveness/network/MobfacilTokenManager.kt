package com.solistra.liveness.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thread-safe OAuth2 token manager for MobFácil Keycloak IDP through KrakenD Gateway.
 *
 * Implements:
 * - Flow 1: Company client_credentials authentication
 * - Flow 3: Proactive background renewal before token expiry (60s buffer)
 * - Flow 4: Inadimplência (Delinquency detection via invalid_client)
 * - Flow 5: Vazamento do Secret (Rotation/leak detection via unauthorized_client)
 * - Concurrency protection via Coroutine Mutex
 * - Resilient DNS fallback via MobfacilDns
 * - Secure persistence across app restarts via SharedPreferences
 */
class MobfacilTokenManager @JvmOverloads constructor(
    val config: MobfacilAuthConfig = MobfacilAuthConfig.staging(),
    customOkHttpClient: OkHttpClient? = null
) {
    private val tag = "MobfacilTokenManager"

    private val mutex = Mutex()

    @Volatile
    private var cachedToken: MobfacilToken? = null

    @Volatile
    private var sharedPreferences: SharedPreferences? = null

    val okHttpClient: OkHttpClient = customOkHttpClient ?: buildDefaultHttpClient(config)

    /**
     * Initializes Android SharedPreferences persistence for cross-restart token reuse.
     */
    fun initPersistence(context: Context) {
        if (sharedPreferences != null) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPreferences = prefs
        loadTokenFromStorage(prefs)
    }

    /**
     * Retrieves a valid Bearer access token string.
     * Proactively refreshes the token if it is within [MobfacilAuthConfig.renewalBufferSeconds] of expiration.
     *
     * @param forceRefresh Force a new token request regardless of expiration status.
     * @return Raw access token string.
     * @throws IllegalStateException if authentication fails.
     */
    suspend fun getAccessToken(forceRefresh: Boolean = false): String {
        when (val result = authenticate(forceRefresh)) {
            is MobfacilAuthResult.Success -> return result.token.accessToken
            is MobfacilAuthResult.Failure -> {
                Log.e(tag, "Failed to get access token: ${result.message} (${result.errorCode})")
                throw IllegalStateException("MobFácil Auth Error [${result.errorCode}]: ${result.message}")
            }
        }
    }

    /**
     * Performs authentication or returns a valid cached token.
     * Thread-safe: Multiple concurrent callers will wait on the same mutex and share the refreshed token.
     */
    suspend fun authenticate(forceRefresh: Boolean = false): MobfacilAuthResult = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000L

        // Fast path: cached token is valid and not in proactive renewal window
        if (!forceRefresh) {
            val current = cachedToken
            if (current != null && !current.isExpiringSoon(config.renewalBufferSeconds, nowSeconds)) {
                return@withContext MobfacilAuthResult.Success(current)
            }
        }

        // Slow path: acquire mutex to guarantee only one refresh request in flight
        mutex.withLock {
            val lockedNowSeconds = System.currentTimeMillis() / 1000L

            // Double check: Did another thread refresh the token while we were waiting for the lock?
            if (!forceRefresh) {
                val current = cachedToken
                if (current != null && !current.isExpiringSoon(config.renewalBufferSeconds, lockedNowSeconds)) {
                    return@withLock MobfacilAuthResult.Success(current)
                }
            }

            Log.i(tag, "Initiating OAuth2 client_credentials token request to ${config.getFullTokenUrl()}...")
            executeTokenRequest(lockedNowSeconds)
        }
    }

    private fun executeTokenRequest(issuedAtSeconds: Long): MobfacilAuthResult {
        val formBodyBuilder = FormBody.Builder()
            .add("grant_type", config.grantType)
            .add("client_id", config.clientId)
            .add("client_secret", config.clientSecret)

        if (!config.scope.isNullOrEmpty()) {
            formBodyBuilder.add("scope", config.scope)
        }

        val request = Request.Builder()
            .url(config.getFullTokenUrl())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .post(formBodyBuilder.build())
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()
            val statusCode = response.code

            if (response.isSuccessful) {
                try {
                    val token = MobfacilToken.fromJson(responseBody, issuedAtSeconds)
                    cachedToken = token
                    saveTokenToStorage(token)
                    Log.i(tag, "Authentication successful. Token expires in ${token.expiresIn}s (Flow 1 / Flow 3 complete).")
                    MobfacilAuthResult.Success(token)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to parse token JSON response: $responseBody", e)
                    MobfacilAuthResult.Failure.HttpError(
                        code = statusCode,
                        rawBody = responseBody,
                        error = "json_parse_error",
                        errorDescription = "Invalid JSON response from auth server: ${e.message}"
                    )
                }
            } else {
                handleAuthErrorResponse(statusCode, responseBody)
            }
        } catch (e: IOException) {
            Log.e(tag, "Network exception during token request: ${e.message}", e)
            MobfacilAuthResult.Failure.NetworkError(e)
        } catch (e: Exception) {
            Log.e(tag, "Unexpected exception during token request: ${e.message}", e)
            MobfacilAuthResult.Failure.NetworkError(e)
        }
    }

    /**
     * Parses Keycloak/KrakenD error responses and classifies into Flow 4 (delinquency)
     * or Flow 5 (rotated secret) or generic error.
     */
    private fun handleAuthErrorResponse(statusCode: Int, responseBody: String): MobfacilAuthResult.Failure {
        var errorCode: String? = null
        var errorDescription: String? = null

        try {
            if (responseBody.isNotEmpty()) {
                val json = JSONObject(responseBody)
                errorCode = if (json.has("error") && !json.isNull("error")) json.getString("error") else null
                errorDescription = if (json.has("error_description") && !json.isNull("error_description")) {
                    json.getString("error_description")
                } else null
            }
        } catch (ignored: Exception) {
            // Body was not JSON
        }

        return when {
            // Flow 4: Inadimplência (Keycloak disabled client account)
            errorCode.equals("invalid_client", ignoreCase = true) -> {
                Log.w(tag, "Flow 4 Inadimplência detected: Client account is disabled or invalid.")
                MobfacilAuthResult.Failure.LicenseBlocked(
                    description = errorDescription
                        ?: "Licença bloqueada por inadimplência. Verifique seu contrato MobFácil."
                )
            }

            // Flow 5: Vazamento do Secret / Rotated Secret
            errorCode.equals("unauthorized_client", ignoreCase = true) -> {
                Log.w(tag, "Flow 5 Rotated/Leaked Secret detected: Client secret has been rotated.")
                MobfacilAuthResult.Failure.InvalidCredentials(
                    description = errorDescription
                        ?: "Credenciais inválidas ou secret rotacionado. Atualize as credenciais no SDK."
                )
            }

            else -> {
                Log.w(tag, "Auth request returned HTTP $statusCode: $errorCode - $errorDescription")
                MobfacilAuthResult.Failure.HttpError(
                    code = statusCode,
                    rawBody = responseBody,
                    error = errorCode,
                    errorDescription = errorDescription
                )
            }
        }
    }

    fun getCachedToken(): MobfacilToken? = cachedToken

    fun invalidateCache() {
        cachedToken = null
        sharedPreferences?.edit()?.clear()?.apply()
        Log.d(tag, "Token cache invalidated.")
    }

    private fun saveTokenToStorage(token: MobfacilToken) {
        val prefs = sharedPreferences ?: return
        try {
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token.accessToken)
                .putString(KEY_TOKEN_TYPE, token.tokenType)
                .putLong(KEY_EXPIRES_IN, token.expiresIn)
                .putLong(KEY_ISSUED_AT, token.issuedAtEpochSeconds)
                .putString(KEY_SCOPE, token.scope)
                .putString(KEY_CLIENT_ID, config.clientId)
                .apply()
        } catch (e: Exception) {
            Log.w(tag, "Failed to persist token to SharedPreferences", e)
        }
    }

    private fun loadTokenFromStorage(prefs: SharedPreferences) {
        try {
            val savedClientId = prefs.getString(KEY_CLIENT_ID, null)
            if (savedClientId != config.clientId) {
                // Client ID changed, do not use cached token
                prefs.edit().clear().apply()
                return
            }

            val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return
            val tokenType = prefs.getString(KEY_TOKEN_TYPE, "Bearer") ?: "Bearer"
            val expiresIn = prefs.getLong(KEY_EXPIRES_IN, 300L)
            val issuedAt = prefs.getLong(KEY_ISSUED_AT, 0L)
            val scope = prefs.getString(KEY_SCOPE, null)

            val token = MobfacilToken(
                accessToken = accessToken,
                tokenType = tokenType,
                expiresIn = expiresIn,
                issuedAtEpochSeconds = issuedAt,
                scope = scope
            )

            val nowSeconds = System.currentTimeMillis() / 1000L
            if (!token.isExpired(nowSeconds)) {
                cachedToken = token
                Log.d(tag, "Loaded valid cached token from storage (expires in ${token.remainingSeconds(nowSeconds)}s).")
            } else {
                Log.d(tag, "Cached token in storage is expired. Clearing.")
                prefs.edit().clear().apply()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to load cached token from SharedPreferences", e)
        }
    }

    companion object {
        private const val PREFS_NAME = "mobfacil_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_TYPE = "token_type"
        private const val KEY_EXPIRES_IN = "expires_in"
        private const val KEY_ISSUED_AT = "issued_at"
        private const val KEY_SCOPE = "scope"
        private const val KEY_CLIENT_ID = "client_id"

        @Volatile
        private var defaultInstance: MobfacilTokenManager? = null

        fun getInstance(
            config: MobfacilAuthConfig = MobfacilAuthConfig.staging(),
            context: Context? = null
        ): MobfacilTokenManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: MobfacilTokenManager(config).also { instance ->
                    context?.let { instance.initPersistence(it) }
                    defaultInstance = instance
                }
            }
        }

        fun buildDefaultHttpClient(config: MobfacilAuthConfig): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)

            if (config.enableDnsFallback) {
                builder.dns(MobfacilDns.INSTANCE)
            }

            return builder.build()
        }
    }
}
