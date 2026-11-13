package com.solistra.liveness.network

import com.solistra.liveness.core.LivenessChallenge
import com.solistra.liveness.core.LivenessResult
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MobfacilCollectorServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun createConfig(): MobfacilAuthConfig {
        val serverUrl = mockWebServer.url("/").toString().trimEnd('/')
        return MobfacilAuthConfig(
            baseUrl = serverUrl,
            tokenEndpoint = "/login/token",
            dataEndpoint = "/collector/liveness",
            clientId = "sdk_ken_QA",
            clientSecret = "nkO2atoPD3po2U3vPYQz8m8V1LXq84fU",
            renewalBufferSeconds = 60L,
            enableDnsFallback = false
        )
    }

    @Test
    fun `flow 2 - upload sends bearer token and liveness telemetry`() = runTest {
        val authResponse = """
            {
              "access_token": "valid_bearer_token",
              "expires_in": 300,
              "token_type": "Bearer"
            }
        """.trimIndent()

        val collectorResponse = """
            {
              "status": "success",
              "transaction_id": "tx_98765"
            }
        """.trimIndent()

        // 1. Auth response
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(authResponse))
        // 2. Collector response
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(collectorResponse))

        val config = createConfig()
        val tokenManager = MobfacilTokenManager(config, okHttpClient)
        val collectorService = MobfacilCollectorService(tokenManager, config, okHttpClient)

        val livenessResult = LivenessResult(
            isLive = true,
            passiveScore = 0.94f,
            completedChallenges = listOf(LivenessChallenge.BLINK, LivenessChallenge.SMILE),
            bestFaceImage = null,
            sessionDurationMs = 2300L
        )

        val uploadOutcome = collectorService.uploadLivenessData(
            result = livenessResult,
            customMetadata = mapOf("user_id" to "usr_123")
        )

        assertTrue(uploadOutcome is MobfacilUploadResult.Success)
        val success = uploadOutcome as MobfacilUploadResult.Success
        assertEquals(200, success.statusCode)
        assertTrue(success.responseBody.contains("tx_98765"))

        // Verify request 1 was auth
        val authReq = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(authReq)
        assertEquals("/login/token", authReq?.path)

        // Verify request 2 was collector with Bearer header
        val dataReq = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(dataReq)
        assertEquals("/collector/liveness", dataReq?.path)
        assertEquals("Bearer valid_bearer_token", dataReq?.getHeader("Authorization"))
        assertTrue(dataReq?.getHeader("Content-Type")?.startsWith("application/json") == true)

        val dataBody = dataReq?.body?.readUtf8().orEmpty()
        assertTrue(dataBody.contains("\"is_live\":true"))
        assertTrue(dataBody.contains("\"session_duration_ms\":2300"))
        assertTrue(dataBody.contains("\"completed_challenges\":[\"BLINK\",\"SMILE\"]"))
        assertTrue(dataBody.contains("\"user_id\":\"usr_123\""))
    }

    @Test
    fun `flow 2 alt - 401 unauthorized triggers token renewal and successful retry`() = runTest {
        val initialAuth = """
            {
              "access_token": "stale_token",
              "expires_in": 300,
              "token_type": "Bearer"
            }
        """.trimIndent()

        val renewedAuth = """
            {
              "access_token": "fresh_renewed_token",
              "expires_in": 300,
              "token_type": "Bearer"
            }
        """.trimIndent()

        val collectorSuccess = """
            {
              "status": "success",
              "message": "Accepted after retry"
            }
        """.trimIndent()

        // 1. Initial auth -> gets "stale_token"
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(initialAuth))
        // 2. Collector returns 401 Unauthorized
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\": \"token_expired\"}"))
        // 3. Forced renewal auth -> gets "fresh_renewed_token"
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(renewedAuth))
        // 4. Collector retry -> succeeds 200 OK
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(collectorSuccess))

        val config = createConfig()
        val tokenManager = MobfacilTokenManager(config, okHttpClient)
        val collectorService = MobfacilCollectorService(tokenManager, config, okHttpClient)

        val livenessResult = LivenessResult(
            isLive = true,
            passiveScore = 0.88f,
            completedChallenges = listOf(LivenessChallenge.BLINK),
            bestFaceImage = null,
            sessionDurationMs = 1500L
        )

        val outcome = collectorService.uploadLivenessData(livenessResult)

        assertTrue(outcome is MobfacilUploadResult.Success)
        val success = outcome as MobfacilUploadResult.Success
        assertEquals(200, success.statusCode)

        // 4 total requests executed: auth -> upload (401) -> renew auth -> upload retry (200)
        assertEquals(4, mockWebServer.requestCount)

        val req1 = mockWebServer.takeRequest(1, TimeUnit.SECONDS) // initial auth
        val req2 = mockWebServer.takeRequest(1, TimeUnit.SECONDS) // first upload attempt
        assertEquals("Bearer stale_token", req2?.getHeader("Authorization"))

        val req3 = mockWebServer.takeRequest(1, TimeUnit.SECONDS) // renewed auth
        val req4 = mockWebServer.takeRequest(1, TimeUnit.SECONDS) // retry upload
        assertEquals("Bearer fresh_renewed_token", req4?.getHeader("Authorization"))
    }
}
