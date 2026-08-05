package com.thecryptosteer.xmrminer.telemetry

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.thecryptosteer.xmrminer.BuildConfig
import com.thecryptosteer.xmrminer.data.MiningRepository
import com.thecryptosteer.xmrminer.domain.MiningState
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class InstallationTelemetryManager(
    context: Context,
    private val repository: MiningRepository,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val identity = InstallationIdentity(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    @Volatile private var foreground = false
    @Volatile private var registered = false
    @Volatile private var accountLinked = false
    private var loop: Job? = null

    val clientInstallationId: String get() = identity.clientInstallationId

    fun setAccountLinked(linked: Boolean) {
        accountLinked = linked
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        loop = scope.launch {
            var failures = 0
            while (isActive) {
                if (!repository.settings.first().disclosureAccepted) {
                    delay(1_000L)
                    continue
                }
                val snapshot = repository.snapshot.value
                val mining = snapshot.state == MiningState.MINING_USER || snapshot.state == MiningState.MINING_DONATION
                val result = runCatching {
                    if (!registered) register()
                    heartbeat(snapshot.state.name, snapshot.hashrate, mining)
                }
                if (result.isSuccess) failures = 0 else {
                    failures = (failures + 1).coerceAtMost(6)
                    if ((result.exceptionOrNull() as? InstallationHttpException)?.status == 401) registered = false
                }
                val normalDelay = if (foreground || mining) 1_000L else 15_000L
                val backoff = if (failures == 0) normalDelay else maxOf(normalDelay, 1_000L shl failures)
                delay(backoff.coerceAtMost(60_000L))
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) { foreground = true }
    override fun onStop(owner: LifecycleOwner) { foreground = false }

    private fun register() {
        val request = Request.Builder()
            .url("${BuildConfig.CONTROL_API_BASE_URL}/v1/installations/register")
            .post(identity.registrationJson().toString().toRequestBody(jsonType))
            .build()
        execute(request)
        registered = true
    }

    private fun heartbeat(state: String, hashrate: Double, mining: Boolean) {
        val body = JSONObject()
            .put("state", state)
            .put("hashrate", hashrate.coerceAtLeast(0.0))
            .put("mining", mining)
            .put("foreground", foreground)
            .put("accountLinked", accountLinked)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .toString()
        val request = Request.Builder()
            .url("${BuildConfig.CONTROL_API_BASE_URL}/v1/installations/heartbeat")
            .header("Authorization", "Bearer ${identity.secret}")
            .post(body.toRequestBody(jsonType))
            .build()
        execute(request)
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
                    .getOrDefault("Installation telemetry failed with HTTP ${response.code}.")
                throw InstallationHttpException(response.code, message)
            }
            return raw
        }
    }
}

class InstallationHttpException(val status: Int, message: String) : IOException(message)
