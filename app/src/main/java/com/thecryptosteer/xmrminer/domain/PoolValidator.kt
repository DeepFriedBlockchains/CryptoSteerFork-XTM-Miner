package com.thecryptosteer.xmrminer.domain

import java.net.IDN

object PoolValidator {
    private val hostLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val sha256 = Regex("^(?:[A-Fa-f0-9]{2}:?){32}$")

    data class Result(val valid: Boolean, val errors: List<String>)

    data class HostInput(val host: String, val tlsHint: Boolean?)

    fun defaultTlsForPort(port: Int): Boolean = port == 443 || port == 20128

    fun parseHostInput(value: String): HostInput {
        val trimmed = value.trim().removeSuffix("/")
        require(trimmed.isNotBlank()) { "Pool URL is required." }
        val split = trimmed.split("://", limit = 2)
        val scheme = if (split.size == 2) split[0].lowercase() else ""
        var authority = if (split.size == 2) split[1] else trimmed
        authority = authority.substringBefore('/').trim()
        val tlsHint = when (scheme) {
            "stratum+ssl", "stratum+tls", "tls", "ssl", "https" -> true
            "stratum+tcp", "tcp", "http" -> false
            "" -> null
            else -> error("Unsupported pool scheme: $scheme")
        }
        val host = when {
            authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
            authority.count { it == ':' } == 1 && authority.substringAfterLast(':').all(Char::isDigit) -> authority.substringBeforeLast(':')
            else -> authority
        }.removePrefix("[").removeSuffix("]")
        require(host.isNotBlank()) { "Pool URL does not contain a host." }
        return HostInput(host, tlsHint)
    }

    fun validate(profile: PoolProfile): Result {
        val errors = mutableListOf<String>()
        if (profile.name.trim().length !in 1..80) errors += "Profile name must be 1–80 characters."
        if (profile.username.isBlank() || profile.username.length > 512) errors += "Wallet or pool username is required and must be at most 512 characters."
        if (profile.worker.isBlank() || profile.worker.length > 128) errors += "Worker name is required and must be at most 128 characters."
        if (profile.rigId.length > 128) errors += "Rig ID must be at most 128 characters."
        if (profile.connectTimeoutSeconds !in 3..120) errors += "Connection timeout must be 3–120 seconds."
        if (profile.retryLimit !in 0..100) errors += "Retry limit must be 0–100."
        validateEndpoint(profile.primary, "Primary", errors)
        profile.failovers.forEachIndexed { index, endpoint -> validateEndpoint(endpoint, "Failover ${index + 1}", errors) }
        return Result(errors.isEmpty(), errors)
    }

    fun parseAddress(address: String, defaultTls: Boolean = true): PoolEndpoint {
        val trimmed = address.trim()
        require(trimmed.length in 3..512) { "Pool address has an invalid length." }
        val schemeSplit = trimmed.split("://", limit = 2)
        val hasScheme = schemeSplit.size == 2
        val scheme = if (hasScheme) schemeSplit[0].lowercase() else ""
        val authority = if (hasScheme) schemeSplit[1] else trimmed
        val tls = when (scheme) {
            "stratum+ssl", "stratum+tls", "tls", "ssl" -> true
            "stratum+tcp", "tcp" -> false
            "" -> defaultTls
            else -> error("Unsupported pool scheme: $scheme")
        }
        val closing = authority.lastIndexOf(']')
        val separator = if (authority.startsWith("[") && closing >= 0) authority.indexOf(':', closing) else authority.lastIndexOf(':')
        require(separator > 0 && separator < authority.lastIndex) { "Pool address must include host and port." }
        val rawHost = authority.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = authority.substring(separator + 1).toIntOrNull() ?: error("Pool port is not a number.")
        val endpoint = PoolEndpoint(rawHost, port, tls)
        val errors = mutableListOf<String>()
        validateEndpoint(endpoint, "Pool", errors)
        require(errors.isEmpty()) { errors.joinToString(" ") }
        return endpoint
    }

    private fun validateEndpoint(endpoint: PoolEndpoint, label: String, errors: MutableList<String>) {
        if (endpoint.host.isBlank() || endpoint.host.length > 253 || !validHost(endpoint.host)) errors += "$label host is invalid."
        if (endpoint.port !in 1..65535) errors += "$label port must be 1–65535."
        if (endpoint.serverName.length > 253 || (endpoint.serverName.isNotBlank() && !validHost(endpoint.serverName))) errors += "$label TLS server name is invalid."
        if (endpoint.pinnedSha256.isNotBlank() && !sha256.matches(endpoint.pinnedSha256)) errors += "$label certificate fingerprint must be a SHA-256 fingerprint."
    }

    private fun validHost(value: String): Boolean {
        val host = value.removePrefix("[").removeSuffix("]")
        if (host.contains(':')) return host.matches(Regex("^[0-9A-Fa-f:]+$"))
        if (host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))) {
            return host.split('.').all { it.toIntOrNull() in 0..255 }
        }
        val ascii = runCatching { IDN.toASCII(host) }.getOrNull() ?: return false
        return ascii.length <= 253 && ascii.split('.').all { hostLabel.matches(it) }
    }
}
