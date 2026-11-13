package com.solistra.liveness.network

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.solistra.liveness.core.LivenessResult
import com.solistra.liveness.ui.LivenessSDKResultHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Service contract for sending verified liveness detection telemetry and biometric snapshots
 * to an upstream collector service.
 */
interface LivenessCollectorService {
    suspend fun uploadLivenessData(
        result: LivenessResult,
        customMetadata: Map<String, Any?> = emptyMap()
    ): MobfacilUploadResult
}

/**
 * Implementation of MobFácil Flow 2: Document / Liveness Ingestion to KrakenD API Gateway.
 *
 * Encapsulates:
 * - Base64 JPEG encoding of verified face snapshot
 * - Bearer authorization token injection
 * - Automatic retry on 401 Unauthorized by proactively refreshing the token (Flow 2 Alt)
 * - Automatic error classification
 */
class MobfacilCollectorService @JvmOverloads constructor(
    val tokenManager: MobfacilTokenManager,
    val config: MobfacilAuthConfig = tokenManager.config,
    customOkHttpClient: OkHttpClient? = null
) : LivenessCollectorService {

    private val tag = "MobfacilCollector"
    private val okHttpClient: OkHttpClient = customOkHttpClient ?: tokenManager.okHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun uploadLivenessData(
        result: LivenessResult,
        customMetadata: Map<String, Any?>
    ): MobfacilUploadResult = withContext(Dispatchers.IO) {
        val payload = buildPayload(result, customMetadata)
        val jsonString = payload.toJson()

        try {
            // Attempt 1: Using current valid/cached token
            val token = try {
                tokenManager.getAccessToken(forceRefresh = false)
            } catch (e: Exception) {
                return@withContext MobfacilUploadResult.Failure(
                    statusCode = null,
                    message = "Authentication failed before upload: ${e.message}",
                    errorCode = "AUTH_PRE_UPLOAD_FAILED"
                )
            }

            Log.i(tag, "Dispatching liveness payload to ${config.getFullDataUrl()}...")
            val firstResponse = executePostRequest(config.getFullDataUrl(), token, jsonString)

            // Flow 2 Alt: If 401 Unauthorized received, token expired in flight
            if (firstResponse.statusCode == 401) {
                Log.w(tag, "Received 401 Unauthorized from Gateway. Forcing token renewal and retrying upload once (Flow 2 retry)...")
                val refreshedToken = try {
                    tokenManager.getAccessToken(forceRefresh = true)
                } catch (e: Exception) {
                    return@withContext MobfacilUploadResult.Failure(
                        statusCode = 401,
                        message = "Failed to renew token after 401: ${e.message}",
                        errorCode = "AUTH_RENEWAL_RETRY_FAILED"
                    )
                }

                // Attempt 2: Retry once with fresh token
                val retryResponse = executePostRequest(config.getFullDataUrl(), refreshedToken, jsonString)
                if (retryResponse.isSuccessful) {
                    Log.i(tag, "Upload retry succeeded with 200 OK after token renewal.")
                    return@withContext MobfacilUploadResult.Success(
                        statusCode = retryResponse.statusCode,
                        responseBody = retryResponse.body
                    )
                } else {
                    Log.e(tag, "Upload retry failed with HTTP ${retryResponse.statusCode}: ${retryResponse.body}")
                    return@withContext parseFailureResponse(retryResponse.statusCode, retryResponse.body)
                }
            }

            if (firstResponse.isSuccessful) {
                Log.i(tag, "Upload succeeded with HTTP ${firstResponse.statusCode} (Token Válido).")
                MobfacilUploadResult.Success(
                    statusCode = firstResponse.statusCode,
                    responseBody = firstResponse.body
                )
            } else {
                Log.e(tag, "Upload failed with HTTP ${firstResponse.statusCode}: ${firstResponse.body}")
                parseFailureResponse(firstResponse.statusCode, firstResponse.body)
            }
        } catch (e: IOException) {
            Log.e(tag, "Network error during liveness data upload: ${e.message}", e)
            MobfacilUploadResult.Failure(
                statusCode = null,
                message = e.localizedMessage ?: "Network connection error",
                errorCode = "NETWORK_ERROR",
                isRetriable = true
            )
        } catch (e: Exception) {
            Log.e(tag, "Unexpected error during liveness data upload: ${e.message}", e)
            MobfacilUploadResult.Failure(
                statusCode = null,
                message = e.localizedMessage ?: "Unexpected upload error",
                errorCode = "UNEXPECTED_ERROR",
                isRetriable = false
            )
        }
    }

    private fun executePostRequest(url: String, bearerToken: String, jsonBody: String): NetworkCallResult {
        val requestBody = jsonBody.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val statusCode = response.code
        val body = response.body?.string().orEmpty()
        return NetworkCallResult(
            isSuccessful = response.isSuccessful,
            statusCode = statusCode,
            body = body
        )
    }

    private fun parseFailureResponse(statusCode: Int, body: String): MobfacilUploadResult.Failure {
        var message = "Upload failed with HTTP $statusCode"
        var errorCode = "HTTP_$statusCode"

        try {
            if (body.isNotEmpty()) {
                val json = JSONObject(body)
                if (json.has("error")) {
                    errorCode = json.getString("error")
                }
                if (json.has("error_description")) {
                    message = json.getString("error_description")
                } else if (json.has("message")) {
                    message = json.getString("message")
                }
            }
        } catch (ignored: Exception) {}

        return MobfacilUploadResult.Failure(
            statusCode = statusCode,
            message = message,
            errorCode = errorCode,
            isRetriable = statusCode in 500..599
        )
    }

    private fun buildPayload(result: LivenessResult, customMetadata: Map<String, Any?>): MobfacilLivenessPayload {
        // Prefer full resolution face snapshot if available, otherwise thumbnail
        val faceBitmap = LivenessSDKResultHolder.fullResolutionBitmap ?: result.bestFaceImage
        val base64Image = faceBitmap?.let { encodeBitmapToBase64(it) }

        val metadata = mutableMapOf<String, Any?>(
            "platform" to "android",
            "sdk_name" to "SolistraLivenessSDK",
            "sdk_version" to "1.0.0",
            "os_version" to Build.VERSION.RELEASE,
            "device_manufacturer" to Build.MANUFACTURER,
            "device_model" to Build.MODEL
        )
        metadata.putAll(customMetadata)

        return MobfacilLivenessPayload(
            isLive = result.isLive,
            passiveScore = result.passiveScore,
            completedChallenges = result.completedChallenges.map { it.name },
            sessionDurationMs = result.sessionDurationMs,
            faceImageBase64 = base64Image,
            timestamp = result.timestamp,
            metadata = metadata
        )
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap, quality: Int = 85): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private data class NetworkCallResult(
        val isSuccessful: Boolean,
        val statusCode: Int,
        val body: String
    )
}
