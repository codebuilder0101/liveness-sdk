package com.solistra.liveness.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MobfacilTokenManagerTest {

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
            clientId = "sdk_ken_QA",
            clientSecret = "nkO2atoPD3po2U3vPYQz8m8V1LXq84fU",
            renewalBufferSeconds = 60L,
            enableDnsFallback = false
        )
    }

    @Test
    fun `flow 1 - authentication succeeds and returns valid token`() = runTest {
        val successJson = """
            {
              "access_token": "mock_jwt_token_12345",
              "expires_in": 300,
              "refresh_expires_in": 0,
              "token_type": "Bearer",
              "not-before-policy": 0,
              "scope": "email profile"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(successJson)
        )

        val manager = MobfacilTokenManager(createConfig(), okHttpClient)
        val result = manager.authenticate()

        assertTrue(result is MobfacilAuthResult.Success)
        val token = (result as MobfacilAuthResult.Success).token
        assertEquals("mock_jwt_token_12345", token.accessToken)
        assertEquals(300L, token.expiresIn)
        assertEquals("Bearer", token.tokenType)
        assertFalse(token.isExpired())
        assertFalse(token.isExpiringSoon(60L))

        val recordedRequest = mockWebServer.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("/login/token", recordedRequest?.path)
        assertEquals("POST", recordedRequest?.method)
        val body = recordedRequest?.body?.readUtf8().orEmpty()
        assertTrue(body.contains("client_id=sdk_ken_QA"))
        assertTrue(body.contains("grant_type=client_credentials"))
        assertTrue(body.contains("client_secret=nkO2atoPD3po2U3vPYQz8m8V1LXq84fU"))
    }

    @Test
    fun `flow 3 - proactive renewal refreshes token within 60s window`() = runTest {
        val tokenResponse1 = """
            {
              "access_token": "token_expiring_soon",
              "expires_in": 50,
              "token_type": "Bearer"
            }
        """.trimIndent()

        val tokenResponse2 = """
            {
              "access_token": "fresh_renewed_token",
              "expires_in": 300,
              "token_type": "Bearer"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse1))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse2))

        val manager = MobfacilTokenManager(createConfig(), okHttpClient)

        // Request 1: gets token with 50s expiry (< 60s renewal buffer)
        val token1 = manager.getAccessToken()
        assertEquals("token_expiring_soon", token1)

        // Request 2: Because remaining time (50s) <= renewalBufferSeconds (60s), proactive renewal triggers
        val token2 = manager.getAccessToken()
        assertEquals("fresh_renewed_token", token2)

        // Verify two network calls occurred
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `flow 3 - cached token is reused when more than 60s remaining`() = runTest {
        val tokenResponse = """
            {
              "access_token": "long_lived_token",
              "expires_in": 300,
              "token_type": "Bearer"
            }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(tokenResponse))

        val manager = MobfacilTokenManager(createConfig(), okHttpClient)

        val token1 = manager.getAccessToken()
        val token2 = manager.getAccessToken()

        assertEquals("long_lived_token", token1)
        assertEquals("long_lived_token", token2)

        // Only 1 network request was made; second read was served from cache
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `flow 4 - delinquency returns license blocked error`() = runTest {
        val delinquencyJson = """
            {
              "error": "invalid_client",
              "error_description": "Invalid client or Invalid client credentials"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(delinquencyJson)
        )

        val manager = MobfacilTokenManager(createConfig(), okHttpClient)
        val result = manager.authenticate()

        assertTrue(result is MobfacilAuthResult.Failure.LicenseBlocked)
        val failure = result as MobfacilAuthResult.Failure.LicenseBlocked
        assertEquals("INVALID_CLIENT_DELINQUENCY", failure.errorCode)
        assertTrue(failure.message.contains("Invalid client") || failure.message.contains("inadimplência"))
    }

    @Test
    fun `flow 5 - rotated secret returns invalid credentials error`() = runTest {
        val rotatedSecretJson = """
            {
              "error": "unauthorized_client",
              "error_description": "Invalid client or Invalid client credentials"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(rotatedSecretJson)
        )

        val manager = MobfacilTokenManager(createConfig(), okHttpClient)
        val result = manager.authenticate()

        assertTrue(result is MobfacilAuthResult.Failure.InvalidCredentials)
        val failure = result as MobfacilAuthResult.Failure.InvalidCredentials
        assertEquals("UNAUTHORIZED_CLIENT_ROTATED", failure.errorCode)
        assertTrue(failure.message.contains("Invalid client") || failure.message.contains("rotacionado"))
    }

    @Test
    fun `concurrency - multiple simultaneous token requests execute only one HTTP call`() = runTest {
        val tokenResponse = """
            {
              "access_token": "shared_concurrent_token",
              "expires_in": 300,
              "token_type": "Bearer"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(tokenResponse)
        )

        val manager = MobfacilTokenManager(createConfig(), okHttpClient)

        // Launch 10 simultaneous coroutines requesting token
        val deferredTokens = (1..10).map {
            async {
                manager.getAccessToken()
            }
        }

        val results = deferredTokens.awaitAll()

        // All 10 callers receive the same token
        for (token in results) {
            assertEquals("shared_concurrent_token", token)
        }

        // Only 1 network request was made due to mutex locking
        assertEquals(1, mockWebServer.requestCount)
    }
}

