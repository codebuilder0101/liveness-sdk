package com.solistra.liveness.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobfacilLiveIntegrationTest {

    @Test
    fun `live staging - flow 1 authentication returns valid jwt from mobfacil keycloak`() = runTest {
        val config = MobfacilAuthConfig.staging()
        val tokenManager = MobfacilTokenManager(config)

        val result = tokenManager.authenticate(forceRefresh = true)

        assertTrue("Expected Success but got $result", result is MobfacilAuthResult.Success)
        val token = (result as MobfacilAuthResult.Success).token

        assertNotNull(token.accessToken)
        assertTrue(token.accessToken.startsWith("eyJ")) // Standard JWT header format
        assertEquals("Bearer", token.tokenType)
        assertEquals(300L, token.expiresIn)
        assertFalse(token.isExpired())
    }

    @Test
    fun `live staging - flow 4 invalid or disabled client returns license blocked error`() = runTest {
        val config = MobfacilAuthConfig(
            clientId = "disabled_client_QA",
            clientSecret = "invalid_secret"
        )
        val tokenManager = MobfacilTokenManager(config)

        val result = tokenManager.authenticate(forceRefresh = true)

        assertTrue("Expected LicenseBlocked but got $result", result is MobfacilAuthResult.Failure.LicenseBlocked)
        val failure = result as MobfacilAuthResult.Failure.LicenseBlocked
        assertEquals("INVALID_CLIENT_DELINQUENCY", failure.errorCode)
        assertTrue(failure.message.isNotEmpty())
    }

    @Test
    fun `live staging - flow 5 rotated secret returns invalid credentials error`() = runTest {
        val config = MobfacilAuthConfig(
            clientId = "sdk_ken_QA",
            clientSecret = "COMPLETELY_WRONG_ROTATED_SECRET"
        )
        val tokenManager = MobfacilTokenManager(config)

        val result = tokenManager.authenticate(forceRefresh = true)

        assertTrue("Expected InvalidCredentials but got $result", result is MobfacilAuthResult.Failure.InvalidCredentials)
        val failure = result as MobfacilAuthResult.Failure.InvalidCredentials
        assertEquals("UNAUTHORIZED_CLIENT_ROTATED", failure.errorCode)
        assertTrue(failure.message.isNotEmpty())
    }
}

