package com.thecryptosteer.xmrminer.ui

import android.app.Application
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thecryptosteer.xmrminer.BuildConfig
import com.thecryptosteer.xmrminer.CryptoSteerApplication
import com.thecryptosteer.xmrminer.data.MiningStateBus
import com.thecryptosteer.xmrminer.device.DeviceInfoProvider
import com.thecryptosteer.xmrminer.device.DeviceInfoUiState
import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.AutoTunePhase
import com.thecryptosteer.xmrminer.domain.AutoTuneUiState
import com.thecryptosteer.xmrminer.domain.JitMode
import com.thecryptosteer.xmrminer.domain.MiningState
import com.thecryptosteer.xmrminer.domain.PoolEndpoint
import com.thecryptosteer.xmrminer.domain.PoolProfile
import com.thecryptosteer.xmrminer.domain.PoolValidator
import com.thecryptosteer.xmrminer.domain.PowerProfile
import com.thecryptosteer.xmrminer.domain.RandomXMode
import com.thecryptosteer.xmrminer.domain.TuningSettings
import com.thecryptosteer.xmrminer.nativebridge.NativeMiner
import java.util.Locale
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as CryptoSteerApplication).container
    private val repository = container.repository
    private val cloudControl = container.cloudControl
    private val updateManager = container.updateManager
    private val deviceInfoProvider = DeviceInfoProvider(application.applicationContext)
    private val mutableSettings = MutableStateFlow(AppSettings())
    private val localProfiles = repository.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val localBenchmarkJson = repository.benchmarkJson.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "[]")
    private val localAutoTuneJson = repository.autoTuneJson.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "[]")
    private var locallyRequestedSettings: AppSettings? = null
    private var autoTuneJob: Job? = null
    private var autoTuneStopReason: String? = null
    private var benchmarkJob: Job? = null
    private var deviceInfoJob: Job? = null
    private val mutableAutoTuneState = MutableStateFlow(AutoTuneUiState())
    private val mutableBenchmarkState = MutableStateFlow(BenchmarkUiState())
    private val mutableDiagnosticsState = MutableStateFlow(DiagnosticsUiState())
    private val mutableDeviceInfoState = MutableStateFlow(DeviceInfoUiState())

    val controlState = cloudControl.state
    val updateState = updateManager.state
    val settings: StateFlow<AppSettings> = combine(mutableSettings, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.settings ?: local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val profiles = combine(localProfiles, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.profiles ?: local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val snapshot = combine(repository.snapshot, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.snapshot ?: local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repository.snapshot.value)
    val logs = combine(repository.logs, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.logs ?: local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = combine(repository.history, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.history ?: local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val benchmarkJson = combine(localBenchmarkJson, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.benchmarkJson ?: local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "[]")
    val autoTuneJson = combine(localAutoTuneJson, controlState) { local, control ->
        control.remoteState?.takeIf { control.selectedRigId != null }?.autoTuneJson ?: local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "[]")
    val donationTierState = combine(controlState, snapshot) { control, visibleSnapshot ->
        val signedIn = control.account.session != null
        val localUserNanos = visibleSnapshot.userMiningNanos.coerceAtLeast(0L)
        val localDonationNanos = visibleSnapshot.donationMiningNanos.coerceAtLeast(0L)
        val localActiveNanos = if (Long.MAX_VALUE - localUserNanos < localDonationNanos) {
            Long.MAX_VALUE
        } else {
            localUserNanos + localDonationNanos
        }
        val active = if (signedIn) control.account.accountActiveUserNanos.coerceAtLeast(0L) else localActiveNanos
        val basis = if (signedIn) control.account.currentDonationBasisPoints else BuildConfig.DONATION_BASIS_POINTS
        DonationTierUiState(
            basisPoints = basis,
            activeUserNanos = active,
            remainingNanos = (BuildConfig.DONATION_REDUCTION_THRESHOLD_NANOS - active).coerceAtLeast(0L),
            accountScoped = signedIn,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DonationTierUiState())
    val autoTuneState: StateFlow<AutoTuneUiState> = mutableAutoTuneState.asStateFlow()
    val benchmarkState: StateFlow<BenchmarkUiState> = mutableBenchmarkState.asStateFlow()
    val diagnosticsState: StateFlow<DiagnosticsUiState> = mutableDiagnosticsState.asStateFlow()
    val deviceInfoState: StateFlow<DeviceInfoUiState> = mutableDeviceInfoState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collectLatest { persisted ->
                val requested = locallyRequestedSettings
                if (requested == null || requested == persisted) {
                    mutableSettings.value = persisted
                    if (requested == persisted) locallyRequestedSettings = null
                }
            }
        }
    }

    fun acceptDisclosure() = viewModelScope.launch { repository.acceptDisclosure() }

    fun saveProfile(profile: PoolProfile, password: String?, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        if (isRemoteSelected()) {
            cloudControl.remoteSaveProfile(profile, password)
            onResult(Result.success(Unit))
        } else {
            onResult(runCatching { repository.saveProfile(profile, password) })
        }
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        if (isRemoteSelected()) cloudControl.sendCommand("delete_profile", JSONObject().put("profileId", id), "Delete request sent to the selected rig.")
        else repository.deleteProfile(id)
    }
    fun duplicateProfile(id: String) = viewModelScope.launch {
        if (isRemoteSelected()) cloudControl.sendCommand("duplicate_profile", JSONObject().put("profileId", id), "Duplicate request sent to the selected rig.")
        else repository.duplicateProfile(id)
    }

    fun testProfile(id: String, onResult: (Result<String>) -> Unit) = viewModelScope.launch {
        if (isRemoteSelected()) {
            cloudControl.sendCommand("test_profile", JSONObject().put("profileId", id), "Pool connection test started on the selected rig.")
            onResult(Result.success("Pool connection test started on the selected rig. Its result will be recorded in that rig's logs."))
        } else {
            onResult(runCatching {
                val result = repository.testProfile(id)
                "${result.message} in ${result.latencyMillis} ms over ${if (result.tls) "TLS" else "TCP"}."
            })
        }
    }

    fun saveSettings(value: AppSettings) {
        val previous = mutableSettings.value
        locallyRequestedSettings = value
        mutableSettings.value = value
        viewModelScope.launch {
            try {
                if (isRemoteSelected()) cloudControl.remoteSaveSettings(value) else repository.saveSettings(value)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (locallyRequestedSettings == value) {
                    locallyRequestedSettings = null
                    mutableSettings.value = previous
                }
                MiningStateBus.appendLog("ERROR", "Unable to save tuning settings: ${exception.message}")
            }
        }
    }

    fun start(profileId: String, onResult: (Result<Unit>) -> Unit) = viewModelScope.launch {
        if (isRemoteSelected()) {
            cloudControl.sendCommand("start", JSONObject().put("profileId", profileId), "Start command sent to the selected rig.")
            onResult(Result.success(Unit))
        } else {
            onResult(runCatching { repository.start(profileId, settings.value) })
        }
    }

    fun pause() = if (isRemoteSelected()) cloudControl.sendCommand("pause", message = "Pause command sent to the selected rig.") else repository.pause()
    fun resume() = if (isRemoteSelected()) cloudControl.sendCommand("resume", message = "Resume command sent to the selected rig.") else repository.resume()
    fun stop() = if (isRemoteSelected()) cloudControl.sendCommand("stop", message = "Stop command sent to the selected rig.") else repository.stop()
    fun clearLogs() = if (isRemoteSelected()) cloudControl.sendCommand("clear_logs", message = "Log clear request sent to the selected rig.") else MiningStateBus.clearLogs()

    fun registerAccount(wallet: String, password: String) = cloudControl.register(wallet, password)
    fun loginAccount(wallet: String, password: String) = cloudControl.login(wallet, password)
    fun recoverAccount(wallet: String, recoveryCode: String, newPassword: String) = cloudControl.recover(wallet, recoveryCode, newPassword)
    fun logoutAccount() = cloudControl.logout()
    fun approveDevice(deviceId: String, approve: Boolean) = cloudControl.approveDevice(deviceId, approve)
    fun selectRig(deviceId: String?) {
        cloudControl.selectRig(deviceId)
        if (deviceId == null) viewModelScope.launch { mutableSettings.value = repository.settings.first() }
    }
    fun refreshRigs() = cloudControl.refreshRigs()
    fun skipBiometric() = cloudControl.skipBiometric()
    fun completeBiometricEnrollment(cipher: javax.crypto.Cipher) = cloudControl.completeBiometricEnrollment(cipher)
    fun completeBiometricUnlock(cipher: javax.crypto.Cipher) = cloudControl.completeBiometricUnlock(cipher)
    fun biometricEnrollmentCipher(): javax.crypto.Cipher = cloudControl.biometricVault.encryptionCipher()
    fun biometricUnlockCipher(): javax.crypto.Cipher = cloudControl.biometricVault.decryptionCipher()
    fun biometricError(message: String) = cloudControl.biometricError(message)
    fun checkForUpdates() = updateManager.checkNow()
    fun installUpdate() = updateManager.downloadAndInstall()
    fun canInstallPackages(): Boolean = updateManager.canInstallPackages()
    fun unknownSourcesIntent(): android.content.Intent = updateManager.unknownSourcesIntent()

    private fun isRemoteSelected(): Boolean = controlState.value.selectedRigId != null

    fun refreshDeviceInfo() {
        if (deviceInfoJob?.isActive == true) return
        mutableDeviceInfoState.value = mutableDeviceInfoState.value.copy(loading = true, error = "")
        deviceInfoJob = viewModelScope.launch {
            try {
                val details = withContext(Dispatchers.IO) { deviceInfoProvider.collect() }
                mutableDeviceInfoState.value = DeviceInfoUiState(loading = false, snapshot = details)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableDeviceInfoState.value = mutableDeviceInfoState.value.copy(
                    loading = false,
                    error = exception.message?.take(220)?.replace(Regex("[\r\n]+"), " ") ?: "Unable to read device information.",
                )
                MiningStateBus.appendLog("WARN", "Device information refresh failed: ${exception.message}")
            } finally {
                deviceInfoJob = null
            }
        }
    }

    fun runSelfTests(onResult: (String) -> Unit) = viewModelScope.launch {
        if (isRemoteSelected()) onResult("Diagnostics must be started directly on the selected rig in this release.")
        else onResult(withContext(Dispatchers.Default) { NativeMiner.runSelfTests() })
    }

    fun startBenchmark(
        threads: Int,
        seconds: Int,
        mode: RandomXMode,
        jitMode: JitMode,
        preferredCpus: List<Int>,
    ) {
        if (isRemoteSelected()) {
            mutableBenchmarkState.value = BenchmarkUiState(phase = BenchmarkPhase.FAILED, targetSeconds = seconds, message = "Switch to This device to run a local RandomX benchmark.", error = "Remote benchmark execution is intentionally disabled until result streaming is active.")
            return
        }
        if (benchmarkJob?.isActive == true) return
        if (autoTuneJob?.isActive == true) {
            mutableBenchmarkState.value = BenchmarkUiState(
                phase = BenchmarkPhase.FAILED,
                targetSeconds = seconds,
                message = "Stop auto-tune before running a local benchmark.",
                error = "Auto-tune is currently active.",
            )
            return
        }
        val miningState = snapshot.value.state
        if (miningState != MiningState.STOPPED && miningState != MiningState.ERROR) {
            mutableBenchmarkState.value = BenchmarkUiState(
                phase = BenchmarkPhase.FAILED,
                targetSeconds = seconds,
                message = "Stop mining before running a local benchmark.",
                error = "A mining session is active.",
            )
            return
        }
        benchmarkJob = viewModelScope.launch {
            val targetSeconds = seconds.coerceIn(5, 300)
            val application = getApplication<Application>()
            val batteryManager = application.getSystemService(BatteryManager::class.java)
            val powerManager = application.getSystemService(PowerManager::class.java)
            val batteryStart = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            var thermalPeak = powerManager?.currentThermalStatus ?: 0
            mutableBenchmarkState.value = BenchmarkUiState(
                phase = BenchmarkPhase.PREPARING,
                targetSeconds = targetSeconds,
                message = "Preparing RandomX memory locally. No pool connection is used.",
            )
            val ticker = launch {
                while (isActive) {
                    thermalPeak = maxOf(thermalPeak, powerManager?.currentThermalStatus ?: 0)
                    val progress = runCatching {
                        withContext(Dispatchers.Default) { JSONObject(NativeMiner.benchmarkProgress()) }
                    }.getOrNull()
                    if (progress != null) {
                        val phase = when (progress.optString("phase")) {
                            "RUNNING" -> BenchmarkPhase.RUNNING
                            "CANCELLING" -> BenchmarkPhase.CANCELLING
                            else -> BenchmarkPhase.PREPARING
                        }
                        val elapsedMillis = progress.optLong("elapsedMillis", 0L)
                        mutableBenchmarkState.value = mutableBenchmarkState.value.copy(
                            phase = phase,
                            elapsedSeconds = (elapsedMillis / 1_000L).toInt(),
                            completedHashes = progress.optLong("hashes", 0L),
                            message = when (phase) {
                                BenchmarkPhase.RUNNING -> "Measuring sustained local RandomX performance."
                                BenchmarkPhase.CANCELLING -> "Stopping benchmark workers safely."
                                else -> "Preparing RandomX cache and dataset."
                            },
                        )
                    }
                    delay(250L)
                }
            }
            try {
                val raw = withContext(Dispatchers.Default) {
                    NativeMiner.benchmark(JSONObject().apply {
                        put("threads", threads.coerceIn(1, 64))
                        put("seconds", targetSeconds)
                        put("mode", when (mode) {
                            RandomXMode.AUTO -> "LIGHT"
                            else -> mode.name
                        })
                        put("jitMode", jitMode.name)
                        put("preferredCpus", JSONArray(preferredCpus.distinct().filter { it >= 0 }.take(64)))
                    }.toString())
                }
                val batteryEnd = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val result = JSONObject(raw).apply {
                    put("batteryStartPercent", batteryStart)
                    put("batteryEndPercent", batteryEnd)
                    put("batteryDeltaPercent", if (batteryStart >= 0 && batteryEnd >= 0) batteryEnd - batteryStart else 0)
                    put("thermalPeak", thermalPeak)
                    put("deviceModel", Build.MODEL)
                    put("androidVersion", Build.VERSION.RELEASE)
                }
                val current = JSONArray(benchmarkJson.value)
                current.put(result)
                repository.saveBenchmarkJson(current.toString())
                mutableBenchmarkState.value = BenchmarkUiState(
                    phase = BenchmarkPhase.COMPLETE,
                    elapsedSeconds = targetSeconds,
                    targetSeconds = targetSeconds,
                    completedHashes = result.optLong("hashes"),
                    message = "Benchmark complete.",
                    resultJson = result.toString(),
                )
            } catch (exception: CancellationException) {
                NativeMiner.cancelBenchmark()
                throw exception
            } catch (exception: Exception) {
                val cancelled = exception.message?.contains("cancel", ignoreCase = true) == true
                mutableBenchmarkState.value = BenchmarkUiState(
                    phase = if (cancelled) BenchmarkPhase.IDLE else BenchmarkPhase.FAILED,
                    targetSeconds = targetSeconds,
                    message = if (cancelled) "Benchmark cancelled." else "Benchmark failed.",
                    error = if (cancelled) "" else (exception.message ?: exception.javaClass.simpleName),
                )
                if (!cancelled) MiningStateBus.appendLog("ERROR", "Benchmark failed: ${exception.message}")
            } finally {
                ticker.cancel()
                benchmarkJob = null
            }
        }
    }

    fun cancelBenchmark() {
        if (benchmarkJob?.isActive != true) return
        mutableBenchmarkState.value = mutableBenchmarkState.value.copy(
            phase = BenchmarkPhase.CANCELLING,
            message = "Stopping benchmark workers safely.",
        )
        NativeMiner.cancelBenchmark()
    }

    fun deleteBenchmark(createdAtMillis: Long) = viewModelScope.launch {
        val source = runCatching { JSONArray(benchmarkJson.value) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optLong("createdAtMillis") != createdAtMillis) updated.put(item)
        }
        repository.saveBenchmarkJson(updated.toString())
    }

    fun runDiagnostics() {
        if (isRemoteSelected()) {
            mutableDiagnosticsState.value = DiagnosticsUiState(running = false, checks = defaultDiagnosticChecks(), overallPassed = false, reportJson = "", durationMillis = 0L)
            MiningStateBus.appendLog("INFO", "Switch to This device before running local diagnostics.")
            return
        }
        if (mutableDiagnosticsState.value.running) return
        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            mutableDiagnosticsState.value = DiagnosticsUiState(running = true)
            fun mark(id: String, status: DiagnosticStatus, detail: String = "") {
                mutableDiagnosticsState.value = mutableDiagnosticsState.value.copy(
                    checks = mutableDiagnosticsState.value.checks.map { check ->
                        if (check.id == id) check.copy(status = status, detail = detail) else check
                    },
                )
            }
            mark("native", DiagnosticStatus.RUNNING)
            val nativeDiagnostics = runCatching { withContext(Dispatchers.Default) { JSONObject(NativeMiner.nativeVersion()) } }
            nativeDiagnostics.onSuccess { mark("native", DiagnosticStatus.PASSED, it.optString("nativeVersion", "Native engine loaded")) }
                .onFailure { mark("native", DiagnosticStatus.FAILED, it.message ?: "Native library failed to load") }

            listOf("json", "hex", "donation", "randomx", "jit").forEach { mark(it, DiagnosticStatus.RUNNING) }
            val nativeTests = runCatching { withContext(Dispatchers.Default) { JSONObject(NativeMiner.runSelfTests()) } }
            nativeTests.onSuccess { tests ->
                mark("json", booleanStatus(tests, "json"), booleanDetail(tests, "json"))
                mark("hex", booleanStatus(tests, "hex"), booleanDetail(tests, "hex"))
                mark("donation", booleanStatus(tests, "donation"), booleanDetail(tests, "donation"))
                mark("randomx", booleanStatus(tests, "randomXVector"), booleanDetail(tests, "randomXVector"))
                mark("jit", booleanStatus(tests, "jitMatchesInterpreter"), booleanDetail(tests, "jitMatchesInterpreter"))
            }.onFailure { failure ->
                listOf("json", "hex", "donation", "randomx", "jit").forEach { mark(it, DiagnosticStatus.FAILED, failure.message ?: "Native test did not complete") }
            }

            mark("pool", DiagnosticStatus.RUNNING)
            val poolParserPassed = runCatching {
                val endpoint = PoolValidator.parseAddress("stratum+tcp://pool.example.com:10128")
                endpoint.host == "pool.example.com" && endpoint.port == 10128 && !endpoint.tls
            }.getOrDefault(false)
            mark("pool", if (poolParserPassed) DiagnosticStatus.PASSED else DiagnosticStatus.FAILED, if (poolParserPassed) "TCP and host/port parsing passed" else "Pool address parser returned an unexpected result")

            mark("tls", DiagnosticStatus.RUNNING)
            val tlsPassed = runCatching { SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory != null }.getOrDefault(false)
            mark("tls", if (tlsPassed) DiagnosticStatus.PASSED else DiagnosticStatus.FAILED, if (tlsPassed) "Android TLS provider is available" else "Android TLS provider is unavailable")

            mark("pagesize", DiagnosticStatus.RUNNING)
            val pageSize = nativeDiagnostics.getOrNull()?.optLong("pageSize", snapshot.value.pageSize) ?: snapshot.value.pageSize
            val pageSupported = pageSize == 4_096L || pageSize == 16_384L
            mark(
                "pagesize",
                if (pageSupported) DiagnosticStatus.PASSED else DiagnosticStatus.FAILED,
                when {
                    pageSize == 4_096L -> "Runtime uses 4 KiB pages; the APK remains 16 KiB compatible"
                    pageSize == 16_384L -> "Runtime uses 16 KiB pages"
                    else -> "Runtime page size $pageSize is not supported"
                },
            )

            val checks = mutableDiagnosticsState.value.checks
            val passed = checks.none { it.status == DiagnosticStatus.FAILED }
            val report = JSONObject().apply {
                put("createdAtMillis", System.currentTimeMillis())
                put("device", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("passed", passed)
                put("durationMillis", SystemClock.elapsedRealtime() - startedAt)
                put("checks", JSONArray().apply {
                    checks.forEach { check ->
                        put(JSONObject().apply {
                            put("id", check.id)
                            put("title", check.title)
                            put("status", check.status.name)
                            put("detail", check.detail)
                        })
                    }
                })
            }
            mutableDiagnosticsState.value = mutableDiagnosticsState.value.copy(
                running = false,
                durationMillis = SystemClock.elapsedRealtime() - startedAt,
                overallPassed = passed,
                reportJson = report.toString(2),
            )
        }
    }

    fun runAutoTune(profileId: String) {
        if (isRemoteSelected()) {
            mutableAutoTuneState.value = AutoTuneUiState(phase = AutoTunePhase.FAILED, message = "Switch to This device to run auto-tune.", error = "Remote auto-tune is disabled until full result streaming is active.")
            return
        }
        if (autoTuneJob?.isActive == true) return
        if (benchmarkJob?.isActive == true) {
            mutableAutoTuneState.value = AutoTuneUiState(
                phase = AutoTunePhase.FAILED,
                profileId = profileId,
                message = "Stop the local benchmark before auto-tuning.",
                error = "A benchmark is currently active.",
            )
            return
        }
        val state = snapshot.value.state
        if (state != MiningState.STOPPED && state != MiningState.ERROR) {
            mutableAutoTuneState.value = AutoTuneUiState(
                phase = AutoTunePhase.FAILED,
                profileId = profileId,
                message = "Stop the active mining session before auto-tuning.",
                error = "Mining is currently active.",
            )
            return
        }
        autoTuneStopReason = null
        autoTuneJob = viewModelScope.launch {
            val targetSeconds = 300
            val startedAt = SystemClock.elapsedRealtime()
            val powerManager = getApplication<Application>().getSystemService(PowerManager::class.java)
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptoSteer:AutoTune").apply {
                setReferenceCounted(false)
                acquire((targetSeconds + 180L) * 1_000L)
            }
            mutableAutoTuneState.value = AutoTuneUiState(
                phase = AutoTunePhase.CONNECTING,
                profileId = profileId,
                targetSeconds = targetSeconds,
                message = "Connecting to the selected pool for a live maximum-hashrate test.",
            )
            val ticker = launch {
                while (isActive) {
                    val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt()
                    val thermal = powerManager.currentThermalStatus
                    if (thermal >= PowerManager.THERMAL_STATUS_EMERGENCY && autoTuneStopReason == null) {
                        autoTuneStopReason = "Stopped because Android reported an emergency thermal condition."
                        NativeMiner.cancelAutoTune()
                    }
                    val current = mutableAutoTuneState.value
                    val message = when {
                        autoTuneStopReason != null -> "Stopping the live test safely."
                        elapsed < 15 -> "Connecting, authenticating, and receiving a live RandomX job."
                        elapsed < 90 -> "Preparing RandomX memory and measuring candidate configurations."
                        else -> "Mining live pool work while comparing CPU, memory, and JIT configurations."
                    }
                    mutableAutoTuneState.value = current.copy(
                        phase = if (elapsed < 15) AutoTunePhase.CONNECTING else AutoTunePhase.RUNNING,
                        elapsedSeconds = elapsed,
                        message = message,
                    )
                    delay(200L)
                }
            }
            try {
                val configJson = repository.buildAutoTuneConfig(profileId, targetSeconds)
                val result = withContext(Dispatchers.Default) { NativeMiner.autoTune(configJson) }
                mutableAutoTuneState.value = mutableAutoTuneState.value.copy(
                    phase = AutoTunePhase.SAVING,
                    message = "Saving the winning maximum-hashrate profile.",
                )
                val parsed = JSONObject(result)
                val best = parsed.getJSONObject("best")
                val cpuArray = best.optJSONArray("preferredCpus") ?: JSONArray()
                val cpus = buildList { for (index in 0 until cpuArray.length()) add(cpuArray.getInt(index)) }
                val mode = runCatching { RandomXMode.valueOf(best.optString("mode", "FULL")) }.getOrDefault(RandomXMode.FULL)
                val jit = if (best.optBoolean("jit", true)) JitMode.ENABLED else JitMode.DISABLED
                val current = settings.value
                val tuned = current.tuning.copy(
                    powerProfile = PowerProfile.AUTO_TUNED,
                    threadCount = best.getInt("threads"),
                    cpuUtilizationPercent = 100,
                    dutyCyclePercent = 100,
                    preferredCpus = cpus,
                    randomXMode = mode,
                    jitMode = jit,
                    mineOnlyWhileCharging = false,
                    pauseBelowBatteryPercent = 0,
                    pauseWhenScreenOn = false,
                    wifiOnly = false,
                )
                saveSettings(current.copy(tuning = tuned))
                val history = JSONArray(autoTuneJson.value)
                history.put(parsed)
                repository.saveAutoTuneJson(history.toString())
                mutableAutoTuneState.value = mutableAutoTuneState.value.copy(
                    phase = AutoTunePhase.COMPLETE,
                    elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt(),
                    message = "Auto-tune complete: ${best.getInt("threads")} threads, ${best.optString("mode")}, %.2f H/s, %d accepted shares.".format(Locale.US, best.optDouble("hashrate"), parsed.optLong("acceptedShares")),
                    resultJson = result,
                    error = "",
                )
            } catch (exception: CancellationException) {
                NativeMiner.cancelAutoTune()
                throw exception
            } catch (exception: Exception) {
                val stopReason = autoTuneStopReason
                mutableAutoTuneState.value = mutableAutoTuneState.value.copy(
                    phase = AutoTunePhase.FAILED,
                    elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L).toInt(),
                    message = stopReason ?: "Auto-tune failed.",
                    error = if (stopReason == null) (exception.message ?: exception.javaClass.simpleName) else "",
                )
                if (stopReason == null) MiningStateBus.appendLog("ERROR", "Auto-tune failed: ${exception.message}")
            } finally {
                ticker.cancel()
                if (wakeLock.isHeld) wakeLock.release()
                autoTuneJob = null
                autoTuneStopReason = null
            }
        }
    }

    fun cancelAutoTune() {
        if (autoTuneJob?.isActive != true) return
        autoTuneStopReason = "Stopped by user."
        mutableAutoTuneState.value = mutableAutoTuneState.value.copy(message = "Stopping the live test safely.")
        NativeMiner.cancelAutoTune()
    }

    fun deleteAutoTune(createdAtMillis: Long) = viewModelScope.launch {
        val source = runCatching { JSONArray(autoTuneJson.value) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            if (item.optLong("createdAtMillis") != createdAtMillis) updated.put(item)
        }
        repository.saveAutoTuneJson(updated.toString())
    }

    fun setPowerProfile(profile: PowerProfile) {
        val current = settings.value
        val tuned = when (profile) {
            PowerProfile.ECO -> current.tuning.copy(powerProfile = profile, threadCount = 2, cpuUtilizationPercent = 45, dutyCyclePercent = 70, randomXMode = RandomXMode.LIGHT, mineOnlyWhileCharging = true)
            PowerProfile.BALANCED -> current.tuning.copy(powerProfile = profile, threadCount = 0, cpuUtilizationPercent = 70, dutyCyclePercent = 100, randomXMode = RandomXMode.AUTO, mineOnlyWhileCharging = true)
            PowerProfile.PERFORMANCE -> current.tuning.copy(powerProfile = profile, threadCount = 0, cpuUtilizationPercent = 90, dutyCyclePercent = 100, randomXMode = RandomXMode.FULL, mineOnlyWhileCharging = true)
            PowerProfile.MAXIMUM -> current.tuning.copy(powerProfile = profile, threadCount = 0, cpuUtilizationPercent = 100, dutyCyclePercent = 100, randomXMode = RandomXMode.FULL, jitMode = JitMode.ENABLED, mineOnlyWhileCharging = false, pauseBelowBatteryPercent = 0)
            PowerProfile.AUTO_TUNED -> {
                val history = runCatching { JSONArray(autoTuneJson.value) }.getOrDefault(JSONArray())
                val best = history.optJSONObject(history.length() - 1)?.optJSONObject("best")
                if (best == null) current.tuning.copy(powerProfile = profile)
                else {
                    val cpuArray = best.optJSONArray("preferredCpus") ?: JSONArray()
                    val cpus = buildList { for (index in 0 until cpuArray.length()) add(cpuArray.optInt(index)) }
                    current.tuning.copy(
                        powerProfile = profile,
                        threadCount = best.optInt("threads", current.tuning.threadCount),
                        cpuUtilizationPercent = 100,
                        dutyCyclePercent = 100,
                        preferredCpus = cpus,
                        randomXMode = runCatching { RandomXMode.valueOf(best.optString("mode", "FULL")) }.getOrDefault(RandomXMode.FULL),
                        jitMode = if (best.optBoolean("jit", true)) JitMode.ENABLED else JitMode.DISABLED,
                        mineOnlyWhileCharging = false,
                        pauseBelowBatteryPercent = 0,
                        pauseWhenScreenOn = false,
                        wifiOnly = false,
                    )
                }
            }
            PowerProfile.CUSTOM -> current.tuning.copy(powerProfile = profile)
        }
        saveSettings(current.copy(tuning = tuned))
    }

    fun updateTuning(transform: (TuningSettings) -> TuningSettings) {
        val current = settings.value
        saveSettings(current.copy(tuning = transform(current.tuning).copy(powerProfile = PowerProfile.CUSTOM)))
    }

    fun resetTuning() {
        val current = settings.value
        saveSettings(current.copy(tuning = TuningSettings()))
    }

    fun profileToJson(profile: PoolProfile, includeEncryptedCredential: Boolean): String = JSONObject().apply {
        put("schema", 2)
        put("name", profile.name)
        put("primary", endpointJson(profile.primary))
        put("failovers", JSONArray().apply { profile.failovers.forEach { put(endpointJson(it)) } })
        put("username", profile.username)
        put("credential", if (includeEncryptedCredential) profile.encryptedPassword else "")
        put("worker", profile.worker)
        put("rigId", profile.rigId)
        put("useWorkerAsPassword", profile.useWorkerAsPassword)
        put("keepAlive", profile.keepAlive)
        put("connectTimeoutSeconds", profile.connectTimeoutSeconds)
        put("retryLimit", profile.retryLimit)
        put("notes", profile.notes)
    }.toString(2)

    fun profileFromJson(raw: String): PoolProfile {
        val o = JSONObject(raw)
        require(o.optInt("schema", 1) in 1..2) { "Unsupported profile schema." }
        return PoolProfile(
            name = o.getString("name"),
            primary = endpointFromJson(o.getJSONObject("primary")),
            failovers = buildList {
                val array = o.optJSONArray("failovers") ?: JSONArray()
                for (index in 0 until array.length()) add(endpointFromJson(array.getJSONObject(index)))
            },
            username = o.getString("username"),
            encryptedPassword = o.optString("credential"),
            worker = o.optString("worker"),
            rigId = o.optString("rigId"),
            useWorkerAsPassword = o.optBoolean("useWorkerAsPassword", true),
            keepAlive = o.optBoolean("keepAlive", true),
            connectTimeoutSeconds = o.optInt("connectTimeoutSeconds", 15),
            retryLimit = o.optInt("retryLimit", 12),
            notes = o.optString("notes"),
            defaultProfile = profiles.value.isEmpty(),
        )
    }

    private fun endpointJson(endpoint: PoolEndpoint) = JSONObject().apply {
        put("host", endpoint.host)
        put("port", endpoint.port)
        put("tls", endpoint.tls)
        put("serverName", endpoint.serverName)
        put("pinnedSha256", endpoint.pinnedSha256)
    }

    private fun endpointFromJson(o: JSONObject) = PoolEndpoint(
        o.getString("host"),
        o.getInt("port"),
        o.optBoolean("tls", true),
        o.optString("serverName"),
        o.optString("pinnedSha256"),
    )

    override fun onCleared() {
        runCatching { NativeMiner.cancelBenchmark() }
        runCatching { NativeMiner.cancelAutoTune() }
        benchmarkJob?.cancel()
        autoTuneJob?.cancel()
        deviceInfoJob?.cancel()
        super.onCleared()
    }

    private fun booleanStatus(json: JSONObject, key: String): DiagnosticStatus = if (json.optBoolean(key, false)) DiagnosticStatus.PASSED else DiagnosticStatus.FAILED
    private fun booleanDetail(json: JSONObject, key: String): String = if (json.optBoolean(key, false)) "Passed" else json.optString("error", "Failed")
}
