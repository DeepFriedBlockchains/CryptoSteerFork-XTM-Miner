package com.thecryptosteer.xmrminer.domain

import java.util.UUID

enum class MiningState { STOPPED, STARTING, CONNECTING, INITIALIZING, MINING_USER, MINING_DONATION, PAUSED, STOPPING, ERROR }
enum class PowerProfile { ECO, BALANCED, PERFORMANCE, MAXIMUM, AUTO_TUNED, CUSTOM }
enum class RandomXMode { AUTO, LIGHT, FULL }
enum class JitMode { AUTO, ENABLED, DISABLED }
enum class AutoTunePhase { IDLE, CONNECTING, PREPARING, RUNNING, SAVING, COMPLETE, FAILED }

data class PoolEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean = true,
    val serverName: String = "",
    val pinnedSha256: String = "",
)

data class PoolProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val primary: PoolEndpoint,
    val failovers: List<PoolEndpoint> = emptyList(),
    val username: String,
    val encryptedPassword: String = "",
    val worker: String = "",
    val rigId: String = "",
    val useWorkerAsPassword: Boolean = true,
    val keepAlive: Boolean = true,
    val connectTimeoutSeconds: Int = 15,
    val retryLimit: Int = 12,
    val notes: String = "",
    val enabled: Boolean = true,
    val defaultProfile: Boolean = false,
    val allowInsecureTls: Boolean = false,
)

data class TuningSettings(
    val powerProfile: PowerProfile = PowerProfile.BALANCED,
    val threadCount: Int = 0,
    val cpuUtilizationPercent: Int = 75,
    val dutyCyclePercent: Int = 100,
    val preferredCpus: List<Int> = emptyList(),
    val randomXMode: RandomXMode = RandomXMode.AUTO,
    val jitMode: JitMode = JitMode.AUTO,
    val stopThermalStatus: Int = 5,
    val resumeThermalStatus: Int = 2,
    val mineOnlyWhileCharging: Boolean = true,
    val pauseBelowBatteryPercent: Int = 30,
    val pauseWhenScreenOn: Boolean = false,
    val wifiOnly: Boolean = false,
)

data class AppSettings(
    val disclosureAccepted: Boolean = false,
    val tuning: TuningSettings = TuningSettings(),
)

data class MinerConfig(
    val profileId: String,
    val profileName: String,
    val endpoints: List<PoolEndpoint>,
    val username: String,
    val password: String,
    val worker: String,
    val rigId: String,
    val keepAlive: Boolean,
    val connectTimeoutSeconds: Int,
    val retryLimit: Int,
    val allowInsecureTls: Boolean,
    val tuning: TuningSettings,
    val persistedUserNanos: Long,
    val persistedDonationNanos: Long,
    val donationBasisPoints: Int,
)

data class MiningSnapshot(
    val state: MiningState = MiningState.STOPPED,
    val statusMessage: String = "",
    val hashrate: Double = 0.0,
    val averageHashrate: Double = 0.0,
    val hashes: Long = 0,
    val accepted: Long = 0,
    val rejected: Long = 0,
    val stale: Long = 0,
    val uptimeSeconds: Long = 0,
    val poolLatencyMillis: Long = 0,
    val activePool: String = "",
    val poolConnected: Boolean = false,
    val jobReceived: Boolean = false,
    val workers: Int = 0,
    val thermalStatus: Int = 0,
    val batteryPercent: Int = 0,
    val charging: Boolean = false,
    val pageSize: Long = 0,
    val jitEnabled: Boolean = false,
    val hardwareAes: Boolean = false,
    val requestedRandomXMode: String = "",
    val randomXMode: String = "",
    val datasetProgress: Int = 0,
    val lastJobAtMillis: Long = 0,
    val lastAcceptedAtMillis: Long = 0,
    val lastError: String = "",
    val userMiningNanos: Long = 0,
    val donationMiningNanos: Long = 0,
    val threadHashrates: List<Double> = emptyList(),
    val cpuAssignments: List<Int> = emptyList(),
    val updatedAtMillis: Long = 0,
) {
    val donationPercent: Double
        get() = DonationMath.percent(userMiningNanos, donationMiningNanos)
}

data class AutoTuneUiState(
    val phase: AutoTunePhase = AutoTunePhase.IDLE,
    val profileId: String = "",
    val elapsedSeconds: Int = 0,
    val targetSeconds: Int = 300,
    val message: String = "",
    val resultJson: String = "",
    val error: String = "",
) {
    val running: Boolean
        get() = phase == AutoTunePhase.CONNECTING || phase == AutoTunePhase.PREPARING || phase == AutoTunePhase.RUNNING || phase == AutoTunePhase.SAVING
}

data class LogEntry(val timeMillis: Long, val level: String, val message: String)
data class HashratePoint(val timeMillis: Long, val hashrate: Double, val thermalStatus: Int, val batteryPercent: Int)
data class BenchmarkResult(
    val createdAtMillis: Long,
    val mode: String,
    val threads: Int,
    val durationSeconds: Int,
    val hashes: Long,
    val hashrate: Double,
    val pageSize: Long,
    val jit: Boolean,
    val hardwareAes: Boolean,
)
