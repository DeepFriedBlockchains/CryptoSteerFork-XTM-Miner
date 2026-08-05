package com.thecryptosteer.xmrminer.cloud

import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.JitMode
import com.thecryptosteer.xmrminer.domain.LogEntry
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.MiningState
import com.thecryptosteer.xmrminer.domain.PoolEndpoint
import com.thecryptosteer.xmrminer.domain.PoolProfile
import com.thecryptosteer.xmrminer.ui.decimatePoints
import com.thecryptosteer.xmrminer.domain.PowerProfile
import com.thecryptosteer.xmrminer.domain.RandomXMode
import com.thecryptosteer.xmrminer.domain.TuningSettings
import org.json.JSONArray
import org.json.JSONObject

object CloudJson {
    fun sessionToJson(session: CloudSession): String = JSONObject()
        .put("accessToken", session.accessToken).put("accessExpiresAt", session.accessExpiresAt)
        .put("refreshToken", session.refreshToken).put("userId", session.userId)
        .put("deviceId", session.deviceId).put("walletAddress", session.walletAddress).toString()

    fun sessionFromJson(raw: String): CloudSession = JSONObject(raw).let {
        CloudSession(it.getString("accessToken"), it.getString("accessExpiresAt"), it.getString("refreshToken"), it.getString("userId"), it.getString("deviceId"), it.getString("walletAddress"))
    }

    fun settings(value: AppSettings): JSONObject = JSONObject().put("disclosureAccepted", value.disclosureAccepted).put("tuning", tuning(value.tuning))
    fun tuning(value: TuningSettings): JSONObject = JSONObject()
        .put("powerProfile", value.powerProfile.name).put("threadCount", value.threadCount)
        .put("cpuUtilizationPercent", value.cpuUtilizationPercent).put("dutyCyclePercent", value.dutyCyclePercent)
        .put("preferredCpus", JSONArray(value.preferredCpus)).put("randomXMode", value.randomXMode.name)
        .put("jitMode", value.jitMode.name).put("stopThermalStatus", value.stopThermalStatus)
        .put("resumeThermalStatus", value.resumeThermalStatus).put("mineOnlyWhileCharging", value.mineOnlyWhileCharging)
        .put("pauseBelowBatteryPercent", value.pauseBelowBatteryPercent).put("pauseWhenScreenOn", value.pauseWhenScreenOn)
        .put("wifiOnly", value.wifiOnly)

    fun settings(o: JSONObject): AppSettings = AppSettings(o.optBoolean("disclosureAccepted", true), tuning(o.optJSONObject("tuning") ?: JSONObject()))
    fun tuning(o: JSONObject): TuningSettings = TuningSettings(
        powerProfile = runCatching { PowerProfile.valueOf(o.optString("powerProfile", PowerProfile.BALANCED.name)) }.getOrDefault(PowerProfile.BALANCED),
        threadCount = o.optInt("threadCount", 0), cpuUtilizationPercent = o.optInt("cpuUtilizationPercent", 75).coerceIn(1, 100),
        dutyCyclePercent = o.optInt("dutyCyclePercent", 100).coerceIn(1, 100),
        preferredCpus = o.optJSONArray("preferredCpus").ints(),
        randomXMode = runCatching { RandomXMode.valueOf(o.optString("randomXMode", RandomXMode.AUTO.name)) }.getOrDefault(RandomXMode.AUTO),
        jitMode = runCatching { JitMode.valueOf(o.optString("jitMode", JitMode.AUTO.name)) }.getOrDefault(JitMode.AUTO),
        stopThermalStatus = o.optInt("stopThermalStatus", 5), resumeThermalStatus = o.optInt("resumeThermalStatus", 2),
        mineOnlyWhileCharging = o.optBoolean("mineOnlyWhileCharging", true), pauseBelowBatteryPercent = o.optInt("pauseBelowBatteryPercent", 30),
        pauseWhenScreenOn = o.optBoolean("pauseWhenScreenOn", false), wifiOnly = o.optBoolean("wifiOnly", false),
    )

    fun endpoint(e: PoolEndpoint): JSONObject = JSONObject().put("host", e.host).put("port", e.port).put("tls", e.tls).put("serverName", e.serverName).put("pinnedSha256", e.pinnedSha256)
    fun endpoint(o: JSONObject): PoolEndpoint = PoolEndpoint(o.optString("host"), o.optInt("port"), o.optBoolean("tls", true), o.optString("serverName"), o.optString("pinnedSha256"))
    fun profile(p: PoolProfile, password: String? = null): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).put("primary", endpoint(p.primary))
        .put("failovers", JSONArray().apply { p.failovers.forEach { put(endpoint(it)) } })
        .put("username", p.username).put("password", password ?: "").put("worker", p.worker).put("rigId", p.rigId)
        .put("useWorkerAsPassword", p.useWorkerAsPassword).put("keepAlive", p.keepAlive)
        .put("connectTimeoutSeconds", p.connectTimeoutSeconds).put("retryLimit", p.retryLimit)
        .put("notes", p.notes).put("enabled", p.enabled).put("defaultProfile", p.defaultProfile)
        .put("allowInsecureTls", p.allowInsecureTls)
    fun profile(o: JSONObject): PoolProfile = PoolProfile(
        id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, name = o.optString("name"),
        primary = endpoint(o.optJSONObject("primary") ?: JSONObject()), failovers = o.optJSONArray("failovers").objects(::endpoint),
        username = o.optString("username"), encryptedPassword = "", worker = o.optString("worker"), rigId = o.optString("rigId"),
        useWorkerAsPassword = o.optBoolean("useWorkerAsPassword", true), keepAlive = o.optBoolean("keepAlive", true),
        connectTimeoutSeconds = o.optInt("connectTimeoutSeconds", 15), retryLimit = o.optInt("retryLimit", 12), notes = o.optString("notes"),
        enabled = o.optBoolean("enabled", true), defaultProfile = o.optBoolean("defaultProfile", false), allowInsecureTls = o.optBoolean("allowInsecureTls", false),
    )

    fun snapshot(s: MiningSnapshot): JSONObject = JSONObject()
        .put("state", s.state.name).put("statusMessage", s.statusMessage).put("hashrate", s.hashrate).put("averageHashrate", s.averageHashrate)
        .put("hashes", s.hashes).put("accepted", s.accepted).put("rejected", s.rejected).put("stale", s.stale)
        .put("uptimeSeconds", s.uptimeSeconds).put("poolLatencyMillis", s.poolLatencyMillis).put("activePool", s.activePool)
        .put("poolConnected", s.poolConnected).put("jobReceived", s.jobReceived).put("workers", s.workers)
        .put("thermalStatus", s.thermalStatus).put("batteryPercent", s.batteryPercent).put("charging", s.charging)
        .put("pageSize", s.pageSize).put("jitEnabled", s.jitEnabled).put("hardwareAes", s.hardwareAes)
        .put("requestedRandomXMode", s.requestedRandomXMode).put("randomXMode", s.randomXMode).put("datasetProgress", s.datasetProgress)
        .put("lastJobAtMillis", s.lastJobAtMillis).put("lastAcceptedAtMillis", s.lastAcceptedAtMillis).put("lastError", s.lastError)
        .put("userMiningNanos", s.userMiningNanos).put("donationMiningNanos", s.donationMiningNanos)
        .put("threadHashrates", JSONArray(s.threadHashrates)).put("cpuAssignments", JSONArray(s.cpuAssignments)).put("updatedAtMillis", s.updatedAtMillis)

    fun snapshot(o: JSONObject): MiningSnapshot = MiningSnapshot(
        state = runCatching { MiningState.valueOf(o.optString("state", "STOPPED")) }.getOrDefault(MiningState.STOPPED),
        statusMessage = o.optString("statusMessage"), hashrate = o.optDouble("hashrate"), averageHashrate = o.optDouble("averageHashrate"),
        hashes = o.optLong("hashes"), accepted = o.optLong("accepted"), rejected = o.optLong("rejected"), stale = o.optLong("stale"),
        uptimeSeconds = o.optLong("uptimeSeconds"), poolLatencyMillis = o.optLong("poolLatencyMillis"), activePool = o.optString("activePool"),
        poolConnected = o.optBoolean("poolConnected"), jobReceived = o.optBoolean("jobReceived"), workers = o.optInt("workers"),
        thermalStatus = o.optInt("thermalStatus"), batteryPercent = o.optInt("batteryPercent"), charging = o.optBoolean("charging"),
        pageSize = o.optLong("pageSize"), jitEnabled = o.optBoolean("jitEnabled"), hardwareAes = o.optBoolean("hardwareAes"),
        requestedRandomXMode = o.optString("requestedRandomXMode"), randomXMode = o.optString("randomXMode"), datasetProgress = o.optInt("datasetProgress"),
        lastJobAtMillis = o.optLong("lastJobAtMillis"), lastAcceptedAtMillis = o.optLong("lastAcceptedAtMillis"), lastError = o.optString("lastError"),
        userMiningNanos = o.optLong("userMiningNanos"), donationMiningNanos = o.optLong("donationMiningNanos"),
        threadHashrates = o.optJSONArray("threadHashrates").doubles(), cpuAssignments = o.optJSONArray("cpuAssignments").ints(), updatedAtMillis = o.optLong("updatedAtMillis"),
    )

    fun log(entry: LogEntry): JSONObject = JSONObject().put("timeMillis", entry.timeMillis).put("level", entry.level).put("message", entry.message)
    fun log(o: JSONObject) = LogEntry(o.optLong("timeMillis"), o.optString("level"), o.optString("message"))
    fun point(p: HashratePoint): JSONObject = JSONObject().put("timeMillis", p.timeMillis).put("hashrate", p.hashrate).put("thermalStatus", p.thermalStatus).put("batteryPercent", p.batteryPercent)
    fun point(o: JSONObject) = HashratePoint(o.optLong("timeMillis"), o.optDouble("hashrate"), o.optInt("thermalStatus"), o.optInt("batteryPercent"))

    fun rigPayload(settings: AppSettings, profiles: List<PoolProfile>, snapshot: MiningSnapshot, logs: List<LogEntry>, history: List<HashratePoint>, benchmarkJson: String, autoTuneJson: String): JSONObject = JSONObject()
        .put("settings", settings(settings)).put("profiles", JSONArray().apply { profiles.forEach { put(profile(it)) } })
        .put("snapshot", snapshot(snapshot)).put("logs", JSONArray().apply { logs.takeLast(100).forEach { put(log(it)) } })
        .put("history", JSONArray().apply { decimatePoints(history, 720).forEach { put(point(it)) } })
        .put("recentHistory", JSONArray().apply { history.takeLast(300).forEach { put(point(it)) } })
        .put("benchmarkJson", benchmarkJson).put("autoTuneJson", autoTuneJson)

    fun remote(deviceId: String, online: Boolean, heartbeat: String, o: JSONObject): RemoteRigState = RemoteRigState(
        deviceId, online, heartbeat,
        settings(o.optJSONObject("settings") ?: JSONObject()), o.optJSONArray("profiles").objects(::profile),
        snapshot(o.optJSONObject("snapshot") ?: JSONObject()), o.optJSONArray("logs").objects(::log),
        mergeHistory(o.optJSONArray("history").objects(::point), o.optJSONArray("recentHistory").objects(::point)),
        o.optString("benchmarkJson", "[]"), o.optString("autoTuneJson", "[]"),
    )

    private fun mergeHistory(summary: List<HashratePoint>, recent: List<HashratePoint>): List<HashratePoint> =
        (summary + recent).associateBy { it.timeMillis }.values.sortedBy { it.timeMillis }
}

private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else buildList { for (i in 0 until length()) add(optInt(i)) }
private fun JSONArray?.doubles(): List<Double> = if (this == null) emptyList() else buildList { for (i in 0 until length()) add(optDouble(i)) }
private fun <T> JSONArray?.objects(mapper: (JSONObject) -> T): List<T> = if (this == null) emptyList() else buildList { for (i in 0 until length()) optJSONObject(i)?.let { add(mapper(it)) } }
