package com.thecryptosteer.xmrminer.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.thecryptosteer.xmrminer.R
import com.thecryptosteer.xmrminer.data.MiningRepository
import com.thecryptosteer.xmrminer.data.DonationPolicyStore
import com.thecryptosteer.xmrminer.service.MiningForegroundService
import com.thecryptosteer.xmrminer.data.MiningStateBus
import com.thecryptosteer.xmrminer.domain.MiningState
import com.thecryptosteer.xmrminer.telemetry.InstallationTelemetryManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class CloudControlManager(
    private val context: Context,
    private val repository: MiningRepository,
    private val donationPolicy: DonationPolicyStore,
    private val installationTelemetry: InstallationTelemetryManager,
) {
    private val api = CloudApi(context)
    private val identity = DeviceIdentity(context)
    private val sessionStore = CloudSessionStore(context)
    val biometricVault = CloudBiometricVault(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ControlUiState(account = AccountControlState(biometricLockedSession = biometricVault.hasSession())))
    val state: StateFlow<ControlUiState> = mutableState.asStateFlow()
    private val active = AtomicBoolean(false)
    private var websocket: WebSocket? = null
    private var telemetryJob: Job? = null
    private var rigsJob: Job? = null
    private var commandsJob: Job? = null
    private var remoteJob: Job? = null
    private var pendingLoginJob: Job? = null
    private val activeCommandIds = ConcurrentHashMap.newKeySet<String>()
    private val commandReceipts = CommandReceiptStore(context)

    init {
        sessionStore.load()?.let { setSession(it, biometricOffer = false, persist = false) }
    }

    fun register(wallet: String, password: String) = authenticate(wallet, password, register = true)
    fun login(wallet: String, password: String) = authenticate(wallet, password, register = false)
    fun recover(wallet: String, recoveryCode: String, newPassword: String) {
        if (wallet.isBlank() || recoveryCode.isBlank() || newPassword.length < 12) {
            mutateAccount { it.copy(error = "Enter the wallet address, one-time support code, and a new password with at least 12 characters.") }
            return
        }
        scope.launch {
            mutateAccount { it.copy(loading = true, error = "", message = "") }
            runCatching { api.recover(wallet, recoveryCode, newPassword, identity) }
                .onSuccess { session -> setSession(session, biometricOffer = true, persist = true); mutateAccount { it.copy(message = "Password recovered. Old sessions remain revoked and this device is now trusted.") } }
                .onFailure { failure("Account recovery failed", it) }
        }
    }

    private fun authenticate(wallet: String, password: String, register: Boolean) {
        if (wallet.isBlank() || password.length < 12) {
            mutateAccount { it.copy(error = "Enter the wallet address and a password with at least 12 characters.") }
            return
        }
        scope.launch {
            mutateAccount { it.copy(loading = true, error = "", message = "") }
            runCatching { if (register) CloudApi.LoginResult.Authenticated(api.register(wallet, password, identity)) else api.login(wallet, password, identity) }
                .onSuccess { result ->
                    when (result) {
                        is CloudApi.LoginResult.Authenticated -> setSession(result.session, biometricOffer = true, persist = true)
                        is CloudApi.LoginResult.ApprovalRequired -> {
                            mutate { it.copy(account = it.account.copy(loading = false, pendingLogin = result.pending, message = "Approve this device from an already trusted rig.")) }
                            pollPendingLogin(wallet, result.pending)
                        }
                    }
                }
                .onFailure { failure("Unable to sign in", it) }
        }
    }

    private fun pollPendingLogin(wallet: String, pending: PendingDeviceLogin) {
        pendingLoginJob?.cancel()
        pendingLoginJob = scope.launch {
            while (isActive) {
                delay(2_000)
                runCatching { api.pendingStatus(pending.token, wallet) }.onSuccess { result ->
                    when (result) {
                        is CloudApi.LoginResult.Authenticated -> { setSession(result.session, biometricOffer = true, persist = true); cancel() }
                        is CloudApi.LoginResult.ApprovalRequired -> Unit
                        null -> { mutateAccount { it.copy(loading = false, pendingLogin = null, error = "The trusted-device request was rejected or expired. Contact dev@thecryptosteer.com if recovery is required.") }; cancel() }
                    }
                }.onFailure { if (it !is CloudHttpException || it.status != 404) failure("Device approval check failed", it) }
            }
        }
    }

    fun logout() {
        val session = state.value.account.session
        pendingLoginJob?.cancel(); stopLoops(); sessionStore.clear(); biometricVault.clear()
        installationTelemetry.setAccountLinked(false)
        mutableState.value = ControlUiState()
        context.stopService(Intent(context, RigSyncForegroundService::class.java))
        if (session != null) scope.launch { runCatching { api.logout(session) } }
    }

    fun skipBiometric() = mutateAccount { it.copy(biometricOffer = false, message = "Login is stored with Android Keystore encryption. Biometric unlock was not enabled.") }
    fun biometricSessionJson(): String = CloudJson.sessionToJson(requireNotNull(state.value.account.session))
    fun completeBiometricEnrollment(cipher: javax.crypto.Cipher) {
        biometricVault.store(cipher, biometricSessionJson())
        sessionStore.clear()
        mutateAccount { it.copy(biometricOffer = false, biometricLockedSession = false, message = "Fingerprint or face login is enabled for the Control tab.") }
    }
    fun completeBiometricUnlock(cipher: javax.crypto.Cipher) = runCatching { CloudJson.sessionFromJson(biometricVault.decrypt(cipher)) }
        .onSuccess { setSession(it, biometricOffer = false, persist = false) }
        .onFailure { failure("Biometric login failed", it) }
    fun biometricError(message: String) = mutateAccount { it.copy(error = message) }

    fun activate() {
        if (active.compareAndSet(false, true)) startLoops()
    }

    fun selectRig(deviceId: String?) {
        val current = state.value.rigs.firstOrNull { it.deviceId == deviceId }?.currentDevice == true
        val selected = if (current) null else deviceId
        mutate { it.copy(selectedRigId = selected, remoteState = if (selected == null) null else it.remoteState, commandMessage = "") }
        remoteJob?.cancel()
        if (selected != null) remoteJob = scope.launch {
            while (isActive) {
                runCatching { api.rigState(requireSession().accessToken, selected) }
                    .onSuccess { remote -> mutate { it.copy(remoteState = remote) } }
                    .onFailure { if (it is CloudHttpException && it.status == 401) refreshSession() }
                delay(1_000)
            }
        }
    }

    fun refreshRigs() = scope.launch { refreshRigsInternal() }
    fun approveDevice(deviceId: String, approve: Boolean) = scope.launch {
        runCatching { api.resolveApproval(requireSession().accessToken, deviceId, approve) }
            .onSuccess { refreshApprovals(); mutate { it.copy(commandMessage = if (approve) "Device approved." else "Device request rejected.") } }
            .onFailure { failure("Unable to update device approval", it) }
    }

    fun sendCommand(type: String, payload: JSONObject = JSONObject(), message: String = "Command sent") {
        val selected = state.value.selectedRigId ?: return
        scope.launch {
            runCatching { api.command(requireSession().accessToken, selected, type, payload) }
                .onSuccess { mutate { it.copy(commandMessage = message) } }
                .onFailure { failure("Remote command failed", it) }
        }
    }

    fun remoteSaveSettings(settings: com.thecryptosteer.xmrminer.domain.AppSettings) = sendCommand("save_settings", CloudJson.settings(settings), "Settings sent to the selected rig.")
    fun remoteSaveProfile(profile: com.thecryptosteer.xmrminer.domain.PoolProfile, password: String?) = sendCommand("save_profile", JSONObject().put("profile", CloudJson.profile(profile, password)).put("password", password ?: ""), "Pool profile sent to the selected rig.")

    private fun setSession(session: CloudSession, biometricOffer: Boolean, persist: Boolean) {
        if (persist) sessionStore.save(session)
        installationTelemetry.setAccountLinked(true)
        mutate { it.copy(account = it.account.copy(session = session, pendingLogin = null, biometricOffer = biometricOffer, biometricLockedSession = false, loading = false, error = "", message = "Signed in as ${session.walletAddress.take(12)}…")) }
        startSyncService()
        activate()
    }

    private fun startSyncService() = runCatching {
        ContextCompat.startForegroundService(context, Intent(context, RigSyncForegroundService::class.java).setAction(RigSyncForegroundService.ACTION_START))
    }.onFailure { MiningStateBus.appendLog("WARN", "Remote control service could not start: ${it.message}") }

    private fun startLoops() {
        stopLoops(clearActive = false)
        val session = state.value.account.session ?: run { active.set(false); return }
        connectWebSocket(session.accessToken)
        telemetryJob = scope.launch {
            while (isActive) {
                runCatching { sendTelemetry() }.onFailure { if (it is CloudHttpException && it.status == 401) refreshSession() }
                delay(1_000)
            }
        }
        rigsJob = scope.launch {
            while (isActive) { refreshRigsInternal(); refreshApprovals(); delay(3_000) }
        }
        commandsJob = scope.launch {
            while (isActive) { runCatching { pollCommands() }; delay(5_000) }
        }
    }

    private fun stopLoops(clearActive: Boolean = true) {
        websocket?.close(1000, "stop"); websocket = null
        telemetryJob?.cancel(); rigsJob?.cancel(); commandsJob?.cancel(); remoteJob?.cancel()
        if (clearActive) active.set(false)
    }

    private suspend fun sendTelemetry() {
        val session = requireSession()
        val settings = repository.settings.first()
        val profiles = repository.profiles.first()
        val snapshot = repository.snapshot.value
        val payload = CloudJson.rigPayload(settings, profiles, snapshot, repository.logs.value, repository.history.value, repository.benchmarkJson.first(), repository.autoTuneJson.first())
        val result = api.telemetry(session.accessToken, installationTelemetry.clientInstallationId, snapshot.state.name, snapshot.hashrate, snapshot.state == MiningState.MINING_USER || snapshot.state == MiningState.MINING_DONATION, snapshot.userMiningNanos, snapshot.donationMiningNanos, payload)
        val accountNanos = result.optString("accountActiveMiningNanos", result.optString("accountActiveUserNanos", "0")).toLongOrNull() ?: 0L
        val basisPoints = result.optInt("donationBasisPoints", 200)
        donationPolicy.update(accountNanos, basisPoints)
        mutateAccount { it.copy(accountActiveUserNanos = accountNanos, currentDonationBasisPoints = basisPoints) }
        MiningForegroundService.updateDonationBasisPoints(basisPoints)
    }

    private suspend fun refreshRigsInternal() {
        val session = state.value.account.session ?: return
        runCatching { api.rigs(session.accessToken) }.onSuccess { rigs ->
            mutate { current ->
                val selectedStillExists = current.selectedRigId == null || rigs.any { it.deviceId == current.selectedRigId }
                current.copy(rigs = rigs, selectedRigId = if (selectedStillExists) current.selectedRigId else null, remoteState = if (selectedStillExists) current.remoteState else null)
            }
        }.onFailure { if (it is CloudHttpException && it.status == 401) refreshSession() }
    }

    private suspend fun refreshApprovals() {
        val session = state.value.account.session ?: return
        runCatching { api.pendingApprovals(session.accessToken) }.onSuccess { approvals -> mutate { it.copy(pendingApprovals = approvals) } }
    }

    private suspend fun pollCommands() {
        val session = requireSession()
        api.pendingCommands(session.accessToken).forEach { command -> executeCommand(command) }
    }

    private suspend fun executeCommand(command: JSONObject) {
        val commandId = command.getString("commandId")
        commandReceipts.get(commandId)?.let { receipt ->
            api.ack(requireSession().accessToken, commandId, receipt.success, receipt.result)
            return
        }
        if (!activeCommandIds.add(commandId)) return
        val type = command.getString("type")
        val payload = command.optJSONObject("payload") ?: JSONObject()
        val execution = runCatching {
            when (type) {
                "start" -> repository.start(payload.getString("profileId"))
                "pause" -> repository.pause()
                "resume" -> repository.resume()
                "stop" -> repository.stop()
                "save_settings" -> repository.saveSettings(CloudJson.settings(payload))
                "save_profile" -> {
                    val profileObject = payload.optJSONObject("profile") ?: payload
                    repository.saveProfile(CloudJson.profile(profileObject), payload.optString("password").takeIf { it.isNotBlank() })
                }
                "test_profile" -> {
                    val test = repository.testProfile(payload.getString("profileId"))
                    MiningStateBus.appendLog("INFO", "Remote pool test: ${test.message} in ${test.latencyMillis} ms over ${if (test.tls) "TLS" else "TCP"}.")
                }
                "delete_profile" -> repository.deleteProfile(payload.getString("profileId"))
                "duplicate_profile" -> repository.duplicateProfile(payload.getString("profileId"))
                "clear_logs" -> MiningStateBus.clearLogs()
                "refresh_state" -> Unit
                else -> error("Unsupported command: $type")
            }
            JSONObject().put("message", "Command completed on ${identity.displayName}.")
        }
        val success = execution.isSuccess
        val result = execution.getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName) }
        commandReceipts.put(commandId, success, result)
        try {
            api.ack(requireSession().accessToken, commandId, success, result)
        } finally {
            activeCommandIds.remove(commandId)
        }
    }

    private fun connectWebSocket(token: String) {
        websocket?.close(1000, "reconnect")
        websocket = api.openWebSocket(token, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return@launch
                    when (envelope.optString("type")) {
                        "command" -> executeCommand(envelope.getJSONObject("data"))
                        "device_approval_requested" -> { refreshApprovals(); showApprovalNotification(envelope.getJSONObject("data")) }
                        "rig_telemetry" -> {
                            val data = envelope.getJSONObject("data")
                            val selected = state.value.selectedRigId
                            if (selected != null && selected == data.optString("deviceId")) {
                                val remote = CloudJson.remote(selected, true, data.optString("receivedAt"), data.getJSONObject("payload"))
                                mutate { it.copy(remoteState = remote) }
                            }
                        }
                        "session_revoked" -> logout()
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { delay(2_000); state.value.account.session?.let { connectWebSocket(it.accessToken) } }
            }
        })
    }

    private fun showApprovalNotification(data: JSONObject) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "cryptosteer_device_approvals"
        manager.createNotificationChannel(NotificationChannel(channelId, "Account device approvals", NotificationManager.IMPORTANCE_HIGH))
        val deviceId = data.optString("deviceId")
        if (deviceId.isBlank()) return
        fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DeviceApprovalReceiver::class.java)
                .setAction(action)
                .putExtra(DeviceApprovalReceiver.EXTRA_DEVICE_ID, deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(deviceId.hashCode(), NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Approve new CryptoSteer device")
            .setContentText("${data.optString("displayName", "A device")} requested access to this wallet account.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Reject", actionIntent(DeviceApprovalReceiver.ACTION_REJECT, deviceId.hashCode() xor 0x522))
            .addAction(0, "Approve", actionIntent(DeviceApprovalReceiver.ACTION_APPROVE, deviceId.hashCode() xor 0x411))
            .setAutoCancel(true)
            .build())
    }

    private suspend fun refreshSession() {
        val current = state.value.account.session ?: return
        runCatching { api.refresh(current) }
            .onSuccess { refreshed ->
                if (!biometricVault.hasSession()) sessionStore.save(refreshed)
                mutateAccount { it.copy(session = refreshed) }
                connectWebSocket(refreshed.accessToken)
            }
            .onFailure { logout(); mutateAccount { it.copy(error = "Your secure session expired. Sign in again.") } }
    }

    private suspend fun requireSession(): CloudSession {
        val session = state.value.account.session ?: error("Login is required.")
        val expiresSoon = runCatching { Instant.parse(session.accessExpiresAt).isBefore(Instant.now().plusSeconds(60)) }.getOrDefault(false)
        if (expiresSoon) refreshSession()
        return state.value.account.session ?: error("Login is required.")
    }

    private fun failure(prefix: String, throwable: Throwable) = mutateAccount { it.copy(loading = false, error = "$prefix: ${throwable.message ?: throwable.javaClass.simpleName}") }
    private fun mutate(transform: (ControlUiState) -> ControlUiState) { mutableState.value = transform(mutableState.value) }
    private fun mutateAccount(transform: (AccountControlState) -> AccountControlState) = mutate { it.copy(account = transform(it.account)) }
}
