package com.thecryptosteer.xmrminer.data

import com.thecryptosteer.xmrminer.BuildConfig
import com.thecryptosteer.xmrminer.domain.PoolEndpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PoolConnectionTester {
    data class Result(
        val endpoint: String,
        val tls: Boolean,
        val latencyMillis: Long,
        val message: String,
        val sessionId: String,
        val jobReceived: Boolean,
    )

    data class LiveJob(
        val endpoint: String,
        val tls: Boolean,
        val latencyMillis: Long,
        val sessionId: String,
        val jobId: String,
        val blobHex: String,
        val seedHashHex: String,
        val targetHex: String,
        val nonceOffset: Int,
        val height: Long,
    )

    fun test(
        endpoint: PoolEndpoint,
        username: String,
        password: String,
        worker: String,
        timeoutSeconds: Int,
        allowInsecureTls: Boolean,
    ): Result {
        val exchange = login(endpoint, username, password, worker, timeoutSeconds, allowInsecureTls, requireJob = false)
        return Result(
            endpoint = "${endpoint.host}:${endpoint.port}",
            tls = endpoint.tls,
            latencyMillis = exchange.latencyMillis,
            message = if (exchange.job != null) "Stratum login accepted and a mining job was received" else "Stratum login accepted; waiting for the first job",
            sessionId = exchange.sessionId,
            jobReceived = exchange.job != null,
        )
    }

    fun fetchLiveJob(
        endpoint: PoolEndpoint,
        username: String,
        password: String,
        worker: String,
        timeoutSeconds: Int,
        allowInsecureTls: Boolean,
    ): LiveJob {
        val exchange = login(endpoint, username, password, worker, timeoutSeconds, allowInsecureTls, requireJob = true)
        val job = requireNotNull(exchange.job) { "The pool accepted login but did not send a mining job before the timeout." }
        val blob = job.optString("blob")
        val seed = job.optString("seed_hash", job.optString("seedHash"))
        val target = job.optString("target")
        val jobId = job.optString("job_id", job.optString("jobId"))
        require(blob.isNotBlank() && seed.isNotBlank() && target.isNotBlank() && jobId.isNotBlank()) {
            "The pool job is missing blob, seed hash, target, or job ID."
        }
        require(blob.length % 2 == 0 && seed.length == 64 && blob.all { isAsciiHexDigit(it) } && seed.all { isAsciiHexDigit(it) }) {
            "The pool returned malformed RandomX job data."
        }
        return LiveJob(
            endpoint = "${endpoint.host}:${endpoint.port}",
            tls = endpoint.tls,
            latencyMillis = exchange.latencyMillis,
            sessionId = exchange.sessionId,
            jobId = jobId,
            blobHex = blob,
            seedHashHex = seed,
            targetHex = target,
            nonceOffset = job.optInt("nonce_offset", 39).coerceAtLeast(0),
            height = job.optLong("height", 0L).coerceAtLeast(0L),
        )
    }

    private data class LoginExchange(
        val latencyMillis: Long,
        val sessionId: String,
        val job: JSONObject?,
    )

    private fun login(
        endpoint: PoolEndpoint,
        username: String,
        password: String,
        worker: String,
        timeoutSeconds: Int,
        allowInsecureTls: Boolean,
        requireJob: Boolean,
    ): LoginExchange {
        val start = System.nanoTime()
        val socket = createSocket(endpoint, timeoutSeconds, allowInsecureTls)
        socket.use {
            val out = BufferedOutputStream(it.getOutputStream())
            val input = BufferedInputStream(it.getInputStream())
            val login = JSONObject().apply {
                put("id", 1)
                put("jsonrpc", "2.0")
                put("method", "login")
                put("params", JSONObject().apply {
                    put("login", username)
                    put("pass", password.ifBlank { worker.ifBlank { "x" } })
                    put("agent", "CryptoSteer/${BuildConfig.VERSION_NAME}")
                    put("algo", JSONArray().put("rx/0"))
                    if (worker.isNotBlank()) put("rigid", worker)
                })
            }.toString() + "\n"
            out.write(login.toByteArray(Charsets.UTF_8))
            out.flush()

            val deadlineNanos = System.nanoTime() + timeoutSeconds.coerceIn(3, 120) * 1_000_000_000L
            var sessionId = ""
            var job: JSONObject? = null
            while (System.nanoTime() < deadlineNanos) {
                val response = readLineBounded(input, 65_536)
                val json = JSONObject(response)
                val error = json.optJSONObject("error")
                if (error != null && error.length() > 0) {
                    error("Pool rejected login: ${error.optString("message", error.toString())}")
                }
                val result = json.optJSONObject("result")
                if (result != null) {
                    val status = result.optString("status", "OK")
                    require(status.equals("OK", ignoreCase = true)) { "Pool rejected login: $status" }
                    sessionId = result.optString("id", sessionId)
                    job = result.optJSONObject("job") ?: job
                }
                if (json.optString("method") == "job") job = json.optJSONObject("params") ?: job
                if (sessionId.isNotBlank() && (!requireJob || job != null)) break
            }
            require(sessionId.isNotBlank()) { "Pool returned an unexpected Stratum login response." }
            if (requireJob) require(job != null) { "Pool login succeeded but no RandomX job arrived before timeout." }
            val latency = (System.nanoTime() - start) / 1_000_000L
            return LoginExchange(latency, sessionId, job)
        }
    }

    private fun createSocket(endpoint: PoolEndpoint, timeoutSeconds: Int, allowInsecureTls: Boolean): Socket {
        val timeout = timeoutSeconds.coerceIn(3, 120) * 1_000
        if (!endpoint.tls) return Socket().apply {
            connect(InetSocketAddress(endpoint.host, endpoint.port), timeout)
            soTimeout = timeout
            tcpNoDelay = true
        }
        val factory = if (allowInsecureTls) insecureFactory() else SSLSocketFactory.getDefault() as SSLSocketFactory
        val raw = Socket().apply {
            connect(InetSocketAddress(endpoint.host, endpoint.port), timeout)
            soTimeout = timeout
            tcpNoDelay = true
        }
        val verifyHost = endpoint.serverName.ifBlank { endpoint.host }
        val tls = factory.createSocket(raw, verifyHost, endpoint.port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            endpointIdentificationAlgorithm = if (allowInsecureTls) null else "HTTPS"
            if (!isIpLiteral(verifyHost)) serverNames = listOf(SNIHostName(verifyHost))
        }
        tls.startHandshake()
        if (endpoint.pinnedSha256.isNotBlank()) {
            val certificate = tls.session.peerCertificates.first() as X509Certificate
            val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
            val expected = endpoint.pinnedSha256.replace(":", "").uppercase()
            require(actual.replace(":", "") == expected) { "TLS certificate fingerprint does not match the configured pin." }
        }
        return tls
    }

    private fun isIpLiteral(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]")
        if (normalized.contains(':')) {
            return runCatching { InetAddress.getByName(normalized) is Inet6Address }.getOrDefault(false)
        }
        val parts = normalized.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
    }

    private fun insecureFactory(): SSLSocketFactory {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }.socketFactory
    }

    private fun readLineBounded(input: BufferedInputStream, limit: Int): String {
        val bytes = ArrayList<Byte>(256)
        while (bytes.size < limit) {
            val value = input.read()
            require(value >= 0) { "Pool closed the connection before sending a response." }
            if (value == '\n'.code) return bytes.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
            bytes += value.toByte()
        }
        error("Pool response exceeded the 64 KiB safety limit.")
    }

    private fun isAsciiHexDigit(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'
}
