package com.solistra.liveness.network

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resilient DNS resolver with fallback for Brazilian .com.br domain delegations.
 *
 * In staging environments, api.stg.mobfacil.com.br delegates to _htz.stg.mobfacil.com.br
 * hosted at IP 65.21.200.181.
 * Certain local Wi-Fi router DNS servers and cellular carriers outside Brazil fail to resolve
 * Brazilian .com.br CNAME delegation chains, throwing UnknownHostException.
 *
 * MobfacilDns attempts default system DNS lookup first. If that fails for MobFácil hostnames,
 * it safely falls back to known resilient IPs without altering the request URL (preserving TLS SNI).
 */
class MobfacilDns(
    private val fallbackHostMap: Map<String, List<String>> = DEFAULT_FALLBACK_HOSTS,
    private val systemDns: Dns = Dns.SYSTEM
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            val systemAddresses = systemDns.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                systemAddresses
            } else {
                resolveFallback(hostname) ?: throw UnknownHostException("No address found for $hostname")
            }
        } catch (e: UnknownHostException) {
            resolveFallback(hostname) ?: throw e
        } catch (e: Exception) {
            resolveFallback(hostname) ?: throw UnknownHostException("DNS resolution failed for $hostname: ${e.message}")
        }
    }

    private fun resolveFallback(hostname: String): List<InetAddress>? {
        val lowerHost = hostname.lowercase()
        val fallbackIps = fallbackHostMap[lowerHost]
            ?: fallbackHostMap.entries.firstOrNull { lowerHost.endsWith(it.key) }?.value

        if (!fallbackIps.isNullOrEmpty()) {
            val resolvedList = mutableListOf<InetAddress>()
            for (ip in fallbackIps) {
                try {
                    val rawIpAddress = InetAddress.getByName(ip).address
                    // Preserve the requested hostname so TLS SNI matching succeeds
                    val aliasedAddress = InetAddress.getByAddress(hostname, rawIpAddress)
                    resolvedList.add(aliasedAddress)
                } catch (ignored: Exception) {
                    // Try next fallback IP
                }
            }
            if (resolvedList.isNotEmpty()) {
                return resolvedList
            }
        }
        return null
    }

    companion object {
        val DEFAULT_FALLBACK_HOSTS: Map<String, List<String>> = mapOf(
            "api.stg.mobfacil.com.br" to listOf("65.21.200.181"),
            "_htz.stg.mobfacil.com.br" to listOf("65.21.200.181")
        )

        val INSTANCE = MobfacilDns()
    }
}

