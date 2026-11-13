package com.solistra.liveness.network

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable OAuth2 access token issued by Keycloak through KrakenD.
 */
@Parcelize
data class MobfacilToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 300L,
    val issuedAtEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    val scope: String? = null
) : Parcelable {

    val expiresAtEpochSeconds: Long
        get() = issuedAtEpochSeconds + expiresIn

    fun remainingSeconds(currentTimeSeconds: Long = System.currentTimeMillis() / 1000L): Long =
        expiresAtEpochSeconds - currentTimeSeconds

    fun isExpired(currentTimeSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        remainingSeconds(currentTimeSeconds) <= 0L

    fun isExpiringSoon(
        bufferSeconds: Long = 60L,
        currentTimeSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean = remainingSeconds(currentTimeSeconds) <= bufferSeconds

    fun authorizationHeader(): String = "$tokenType $accessToken"

    companion object {
        fun fromJson(
            jsonStr: String,
            issuedAtEpochSeconds: Long = System.currentTimeMillis() / 1000L
        ): MobfacilToken {
            val json = JSONObject(jsonStr)
            return MobfacilToken(
                accessToken = json.getString("access_token"),
                tokenType = json.optString("token_type", "Bearer"),
                expiresIn = json.optLong("expires_in", 300L),
                issuedAtEpochSeconds = issuedAtEpochSeconds,
                scope = if (json.has("scope") && !json.isNull("scope")) json.getString("scope") else null
            )
        }
    }
}

/**
 * Result outcome of the MobFácil authentication flow (Flow 1, 4, 5).
 */
sealed class MobfacilAuthResult {
    data class Success(val token: MobfacilToken) : MobfacilAuthResult()

    sealed class Failure(val message: String, val errorCode: String) : MobfacilAuthResult() {
        /**
         * Flow 4: Inadimplência (Delinquency / Payment Default).
         * Account has been disabled in Keycloak (error = invalid_client).
         */
        data class LicenseBlocked(
            val description: String = "Licença bloqueada por inadimplência. Entre em contato com a MobFácil."
        ) : Failure(description, "INVALID_CLIENT_DELINQUENCY")

        /**
         * Flow 5: Vazamento do Secret (Compromised or rotated client secret).
         * Old or rotated secret rejected (error = unauthorized_client).
         */
        data class InvalidCredentials(
            val description: String = "Credenciais inválidas ou secret rotacionado."
        ) : Failure(description, "UNAUTHORIZED_CLIENT_ROTATED")

        /**
         * Generic HTTP error from Gateway or Keycloak.
         */
        data class HttpError(
            val code: Int,
            val rawBody: String,
            val error: String? = null,
            val errorDescription: String? = null
        ) : Failure(
            errorDescription ?: error ?: "HTTP error $code: $rawBody",
            error ?: "HTTP_$code"
        )

        /**
         * Socket / Connection / DNS error.
         */
        data class NetworkError(
            val throwable: Throwable
        ) : Failure(throwable.localizedMessage ?: "Erro de conexão de rede", "NETWORK_ERROR")
    }
}

/**
 * Result of liveness verification data transmission to KrakenD collector (Flow 2).
 */
@Parcelize
sealed class MobfacilUploadResult : Parcelable {
    @Parcelize
    data class Success(
        val statusCode: Int,
        val responseBody: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : MobfacilUploadResult()

    @Parcelize
    data class Failure(
        val statusCode: Int?,
        val message: String,
        val errorCode: String,
        val isRetriable: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : MobfacilUploadResult()
}

/**
 * Liveness verification JSON payload dispatched to the MobFácil KrakenD collector.
 */
data class MobfacilLivenessPayload(
    val isLive: Boolean,
    val passiveScore: Float,
    val completedChallenges: List<String>,
    val sessionDurationMs: Long,
    val faceImageBase64: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any?> = emptyMap()
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("is_live", isLive)
        root.put("passive_score", passiveScore.toDouble())
        root.put("completed_challenges", JSONArray(completedChallenges))
        root.put("session_duration_ms", sessionDurationMs)
        root.put("timestamp", timestamp)
        if (!faceImageBase64.isNullOrEmpty()) {
            root.put("face_image_base64", faceImageBase64)
        }

        val metaObj = JSONObject()
        metadata.forEach { (key, value) ->
            metaObj.put(key, value)
        }
        root.put("metadata", metaObj)
        return root.toString()
    }
}
