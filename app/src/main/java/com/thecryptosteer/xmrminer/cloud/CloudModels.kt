package com.thecryptosteer.xmrminer.cloud

import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.LogEntry
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.PoolProfile

data class CloudSession(
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val userId: String,
    val deviceId: String,
    val walletAddress: String,
)

data class PendingDeviceLogin(val token: String, val expiresAt: String)

data class AccountControlState(
    val session: CloudSession? = null,
    val pendingLogin: PendingDeviceLogin? = null,
    val biometricOffer: Boolean = false,
    val biometricLockedSession: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
    val message: String = "",
    val accountActiveUserNanos: Long = 0L,
    val currentDonationBasisPoints: Int = 200,
)

data class RigSummary(
    val deviceId: String,
    val clientDeviceId: String,
    val displayName: String,
    val platform: String,
    val appVersion: String,
    val online: Boolean,
    val state: String,
    val hashrate: Double,
    val mining: Boolean,
    val currentDevice: Boolean,
    val lastHeartbeat: String?,
)

data class PendingApproval(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val appVersion: String,
    val requestedAt: String,
    val expiresAt: String,
)

data class RemoteRigState(
    val deviceId: String,
    val online: Boolean,
    val lastHeartbeat: String,
    val settings: AppSettings = AppSettings(disclosureAccepted = true),
    val profiles: List<PoolProfile> = emptyList(),
    val snapshot: MiningSnapshot = MiningSnapshot(),
    val logs: List<LogEntry> = emptyList(),
    val history: List<HashratePoint> = emptyList(),
    val benchmarkJson: String = "[]",
    val autoTuneJson: String = "[]",
)

data class ControlUiState(
    val account: AccountControlState = AccountControlState(),
    val rigs: List<RigSummary> = emptyList(),
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val selectedRigId: String? = null,
    val remoteState: RemoteRigState? = null,
    val commandMessage: String = "",
)

data class ReleaseManifest(
    val releaseId: String,
    val platform: String,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val fileSize: Long,
    val downloadUrl: String,
    val mandatory: Boolean,
    val releaseNotes: String,
    val publishedAt: String,
    val signature: String,
    val updateEnforcementEnabled: Boolean,
)

data class UpdateUiState(
    val checking: Boolean = true,
    val blocked: Boolean = false,
    val available: Boolean = false,
    val enforcementEnabled: Boolean = true,
    val manifest: ReleaseManifest? = null,
    val progress: Float = 0f,
    val installing: Boolean = false,
    val error: String = "",
    val message: String = "Checking for required updates…",
)
