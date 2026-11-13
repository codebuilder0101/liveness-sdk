package com.solistra.liveness.network

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class MobfacilDnsTest {

    @Test
    fun `system dns resolution succeeds without fallback`() {
        val mockSystemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }

        val dns = MobfacilDns(systemDns = mockSystemDns)
        val result = dns.lookup("example.com")

        assertEquals(1, result.size)
        assertEquals("127.0.0.1", result[0].hostAddress)
    }

    @Test
    fun `system dns failure falls back to resilient IP for MobFacil domain`() {
        val failingSystemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                throw UnknownHostException("Simulated DNS failure")
            }
        }

        val dns = MobfacilDns(
            fallbackHostMap = mapOf("api.stg.mobfacil.com.br" to listOf("65.21.200.181")),
            systemDns = failingSystemDns
        )

        val result = dns.lookup("api.stg.mobfacil.com.br")

        assertNotNull(result)
        assertTrue(result.isNotEmpty())
        assertEquals("65.21.200.181", result[0].hostAddress)
        // Verify hostname is preserved for TLS SNI
        assertEquals("api.stg.mobfacil.com.br", result[0].hostName)
    }

    @Test(expected = UnknownHostException::class)
    fun `system dns failure throws UnknownHostException for unknown host`() {
        val failingSystemDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                throw UnknownHostException("No address found")
            }
        }

        val dns = MobfacilDns(systemDns = failingSystemDns)
        dns.lookup("unknown.unregistered.domain.xyz")
    }
}

