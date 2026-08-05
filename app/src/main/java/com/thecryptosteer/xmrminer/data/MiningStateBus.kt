package com.thecryptosteer.xmrminer.data

import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.LogEntry
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.MiningState
import com.thecryptosteer.xmrminer.ui.compactHashrateHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object MiningStateBus {
    private const val HISTORY_SAMPLE_MILLIS = 1_000L
    private const val MAX_HISTORY_POINTS = 28_800

    private val mutableSnapshot = MutableStateFlow(MiningSnapshot())
    private val mutableLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    private val mutableHistory = MutableStateFlow<List<HashratePoint>>(emptyList())
    private var lastHistoryAt = 0L

    val snapshot: StateFlow<MiningSnapshot> = mutableSnapshot.asStateFlow()
    val logs: StateFlow<List<LogEntry>> = mutableLogs.asStateFlow()
    val history: StateFlow<List<HashratePoint>> = mutableHistory.asStateFlow()

    @JvmStatic
    fun update(snapshot: MiningSnapshot) {
        mutableSnapshot.value = snapshot.copy(updatedAtMillis = System.currentTimeMillis())
    }

    @JvmStatic
    fun updateFromJson(json: String) {
        val o = JSONObject(json)
        val current = mutableSnapshot.value
        val state = runCatching { MiningState.valueOf(o.optString("state", current.state.name)) }.getOrDefault(current.state)
        val threadRates = buildList {
            val array = o.optJSONArray("threadHashrates")
            if (array != null) for (index in 0 until array.length()) add(array.optDouble(index, 0.0))
        }
        val cpus = buildList {
            val array = o.optJSONArray("cpuAssignments")
            if (array != null) for (index in 0 until array.length()) add(array.optInt(index, -1))
        }
        val now = System.currentTimeMillis()
        mutableSnapshot.value = current.copy(
            state = state,
            statusMessage = o.optString("statusMessage", current.statusMessage),
            hashrate = o.optDouble("hashrate", current.hashrate),
            averageHashrate = o.optDouble("averageHashrate", current.averageHashrate),
            hashes = o.optLong("hashes", current.hashes),
            accepted = o.optLong("accepted", current.accepted),
            rejected = o.optLong("rejected", current.rejected),
            stale = o.optLong("stale", current.stale),
            uptimeSeconds = o.optLong("uptimeSeconds", current.uptimeSeconds),
            poolLatencyMillis = o.optLong("poolLatencyMillis", current.poolLatencyMillis),
            activePool = o.optString("activePool", current.activePool),
            poolConnected = o.optBoolean("poolConnected", current.poolConnected),
            jobReceived = o.optBoolean("jobReceived", current.jobReceived),
            workers = o.optInt("workers", current.workers),
            pageSize = o.optLong("pageSize", current.pageSize),
            jitEnabled = o.optBoolean("jitEnabled", current.jitEnabled),
            hardwareAes = o.optBoolean("hardwareAes", current.hardwareAes),
            requestedRandomXMode = o.optString("requestedRandomXMode", current.requestedRandomXMode),
            randomXMode = o.optString("randomXMode", current.randomXMode),
            datasetProgress = o.optInt("datasetProgress", current.datasetProgress),
            lastJobAtMillis = o.optLong("lastJobAtMillis", current.lastJobAtMillis),
            lastAcceptedAtMillis = o.optLong("lastAcceptedAtMillis", current.lastAcceptedAtMillis),
            lastError = o.optString("lastError", current.lastError),
            userMiningNanos = o.optLong("userMiningNanos", current.userMiningNanos),
            donationMiningNanos = o.optLong("donationMiningNanos", current.donationMiningNanos),
            threadHashrates = threadRates,
            cpuAssignments = cpus,
            updatedAtMillis = now,
        )
        if (now - lastHistoryAt >= HISTORY_SAMPLE_MILLIS && mutableSnapshot.value.hashrate >= 0.0) {
            lastHistoryAt = now
            val value = mutableSnapshot.value
            mutableHistory.value = compactHashrateHistory(
                mutableHistory.value + HashratePoint(now, value.hashrate, value.thermalStatus, value.batteryPercent),
                MAX_HISTORY_POINTS,
            )
        }
    }

    @JvmStatic
    fun updateDevice(thermal: Int, battery: Int, charging: Boolean) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            thermalStatus = thermal,
            batteryPercent = battery,
            charging = charging,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    @JvmStatic
    fun state(state: MiningState, message: String = "") {
        val current = mutableSnapshot.value
        if (state == MiningState.STARTING && current.state in setOf(MiningState.STOPPED, MiningState.ERROR)) {
            mutableHistory.value = emptyList()
            lastHistoryAt = 0L
        }
        mutableSnapshot.value = current.copy(
            state = state,
            statusMessage = message,
            lastError = if (state == MiningState.ERROR && message.isNotBlank()) message else current.lastError,
            poolConnected = if (state == MiningState.STOPPED || state == MiningState.ERROR) false else current.poolConnected,
            jobReceived = if (state == MiningState.STOPPED || state == MiningState.ERROR) false else current.jobReceived,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    @JvmStatic
    fun appendLog(level: String, message: String) {
        val safe = redact(message).take(2_000)
        mutableLogs.value = (mutableLogs.value + LogEntry(System.currentTimeMillis(), level.take(16), safe)).takeLast(500)
    }

    @JvmStatic
    fun clearLogs() {
        mutableLogs.value = emptyList()
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(password|pass|token|secret)\\s*[:=]\\s*[^,\\s]+"), "$1=<redacted>")
        .replace(Regex("4[0-9A-Za-z]{90,110}"), "<wallet-redacted>")
}
