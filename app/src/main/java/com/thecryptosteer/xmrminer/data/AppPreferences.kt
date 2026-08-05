package com.thecryptosteer.xmrminer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.DonationMath
import com.thecryptosteer.xmrminer.domain.JitMode
import com.thecryptosteer.xmrminer.domain.PoolEndpoint
import com.thecryptosteer.xmrminer.domain.PoolProfile
import com.thecryptosteer.xmrminer.domain.PowerProfile
import com.thecryptosteer.xmrminer.domain.RandomXMode
import com.thecryptosteer.xmrminer.domain.TuningSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "cryptosteer_settings")

class AppPreferences(private val context: Context) {
    private companion object {
        const val CURRENT_DISCLOSURE_VERSION = 2
    }

    private object Keys {
        val disclosure = booleanPreferencesKey("disclosure_accepted")
        val disclosureVersion = intPreferencesKey("disclosure_version")
        val tuning = stringPreferencesKey("tuning_json")
        val profiles = stringPreferencesKey("profiles_json")
        val userNanos = longPreferencesKey("user_mining_nanos")
        val donationNanos = longPreferencesKey("donation_mining_nanos")
        val benchmark = stringPreferencesKey("benchmark_results_json")
        val autoTune = stringPreferencesKey("auto_tune_results_json")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            disclosureAccepted = (prefs[Keys.disclosure] ?: false) && (prefs[Keys.disclosureVersion] ?: 1) >= CURRENT_DISCLOSURE_VERSION,
            tuning = decodeTuning(prefs[Keys.tuning]),
        )
    }

    val profiles: Flow<List<PoolProfile>> = context.dataStore.data.map { prefs -> decodeProfiles(prefs[Keys.profiles]) }
    val accounting: Flow<Pair<Long, Long>> = context.dataStore.data.map { prefs ->
        Pair(prefs[Keys.userNanos] ?: 0L, prefs[Keys.donationNanos] ?: 0L)
    }

    suspend fun acceptDisclosure() = context.dataStore.edit {
        it[Keys.disclosure] = true
        it[Keys.disclosureVersion] = CURRENT_DISCLOSURE_VERSION
    }
    suspend fun saveSettings(value: AppSettings) = context.dataStore.edit {
        // Disclosure acceptance is local-only and can only be written by acceptDisclosure().
        it[Keys.tuning] = encodeTuning(value.tuning).toString()
    }
    suspend fun saveProfiles(value: List<PoolProfile>) = context.dataStore.edit { it[Keys.profiles] = encodeProfiles(value).toString() }
    suspend fun saveAccounting(userNanos: Long, donationNanos: Long) = context.dataStore.edit {
        val oldUser = (it[Keys.userNanos] ?: 0L).coerceAtLeast(0L)
        val oldDonation = (it[Keys.donationNanos] ?: 0L).coerceAtLeast(0L)
        val reportedUser = userNanos.coerceAtLeast(0L)
        val reportedDonation = donationNanos.coerceAtLeast(0L)
        val mergedUser = maxOf(oldUser, reportedUser)
        val mergedDonation = maxOf(oldDonation, reportedDonation)
        it[Keys.userNanos] = mergedUser
        it[Keys.donationNanos] = minOf(mergedDonation, DonationMath.maximumDonationForUserTime(mergedUser))
    }
    suspend fun saveBenchmarkJson(value: String) = context.dataStore.edit { it[Keys.benchmark] = value.take(256_000) }
    val benchmarkJson: Flow<String> = context.dataStore.data.map { it[Keys.benchmark] ?: "[]" }
    suspend fun saveAutoTuneJson(value: String) = context.dataStore.edit { it[Keys.autoTune] = value.take(512_000) }
    val autoTuneJson: Flow<String> = context.dataStore.data.map { it[Keys.autoTune] ?: "[]" }

    private fun encodeProfiles(items: List<PoolProfile>): JSONArray = JSONArray().apply {
        items.forEach { profile ->
            put(JSONObject().apply {
                put("id", profile.id); put("name", profile.name); put("primary", encodeEndpoint(profile.primary))
                put("failovers", JSONArray().apply { profile.failovers.forEach { put(encodeEndpoint(it)) } })
                put("username", profile.username); put("encryptedPassword", profile.encryptedPassword)
                put("worker", profile.worker); put("rigId", profile.rigId); put("useWorkerAsPassword", profile.useWorkerAsPassword); put("keepAlive", profile.keepAlive)
                put("connectTimeoutSeconds", profile.connectTimeoutSeconds); put("retryLimit", profile.retryLimit)
                put("notes", profile.notes); put("enabled", profile.enabled); put("defaultProfile", profile.defaultProfile)
                put("allowInsecureTls", profile.allowInsecureTls)
            })
        }
    }

    private fun decodeProfiles(raw: String?): List<PoolProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(PoolProfile(
                    id = o.getString("id"), name = o.getString("name"), primary = decodeEndpoint(o.getJSONObject("primary")),
                    failovers = buildList { val f=o.optJSONArray("failovers") ?: JSONArray(); for (j in 0 until f.length()) add(decodeEndpoint(f.getJSONObject(j))) },
                    username = o.getString("username"), encryptedPassword = o.optString("encryptedPassword"), worker = o.optString("worker"),
                    rigId = o.optString("rigId"), useWorkerAsPassword = o.optBoolean("useWorkerAsPassword", true), keepAlive = o.optBoolean("keepAlive", true), connectTimeoutSeconds = o.optInt("connectTimeoutSeconds", 15),
                    retryLimit = o.optInt("retryLimit", 12), notes = o.optString("notes"), enabled = o.optBoolean("enabled", true),
                    defaultProfile = o.optBoolean("defaultProfile"), allowInsecureTls = o.optBoolean("allowInsecureTls"),
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeEndpoint(e: PoolEndpoint) = JSONObject().apply {
        put("host", e.host); put("port", e.port); put("tls", e.tls); put("serverName", e.serverName); put("pinnedSha256", e.pinnedSha256)
    }
    private fun decodeEndpoint(o: JSONObject) = PoolEndpoint(o.getString("host"), o.getInt("port"), o.optBoolean("tls", true), o.optString("serverName"), o.optString("pinnedSha256"))

    private fun encodeTuning(t: TuningSettings) = JSONObject().apply {
        put("powerProfile", t.powerProfile.name); put("threadCount", t.threadCount); put("cpuUtilizationPercent", t.cpuUtilizationPercent)
        put("dutyCyclePercent", t.dutyCyclePercent); put("preferredCpus", JSONArray(t.preferredCpus)); put("randomXMode", t.randomXMode.name)
        put("jitMode", t.jitMode.name); put("stopThermalStatus", t.stopThermalStatus); put("resumeThermalStatus", t.resumeThermalStatus)
        put("mineOnlyWhileCharging", t.mineOnlyWhileCharging); put("pauseBelowBatteryPercent", t.pauseBelowBatteryPercent)
        put("pauseWhenScreenOn", t.pauseWhenScreenOn); put("wifiOnly", t.wifiOnly)
    }
    private fun decodeTuning(raw: String?): TuningSettings = runCatching {
        val o = JSONObject(raw ?: "{}")
        TuningSettings(
            powerProfile = runCatching { PowerProfile.valueOf(o.optString("powerProfile", PowerProfile.BALANCED.name)) }.getOrDefault(PowerProfile.BALANCED), threadCount = o.optInt("threadCount", 0),
            cpuUtilizationPercent = o.optInt("cpuUtilizationPercent", 75).coerceIn(1,100), dutyCyclePercent = o.optInt("dutyCyclePercent", 100).coerceIn(1,100),
            preferredCpus = buildList { val a=o.optJSONArray("preferredCpus") ?: JSONArray(); for(i in 0 until a.length()) add(a.getInt(i)) },
            randomXMode = runCatching { RandomXMode.valueOf(o.optString("randomXMode", RandomXMode.AUTO.name)) }.getOrDefault(RandomXMode.AUTO),
            jitMode = runCatching { JitMode.valueOf(o.optString("jitMode", JitMode.AUTO.name)) }.getOrDefault(JitMode.AUTO),
            stopThermalStatus = o.optInt("stopThermalStatus", 5).coerceIn(3, 6),
            resumeThermalStatus = o.optInt("resumeThermalStatus", 2).coerceIn(0, o.optInt("stopThermalStatus", 5).coerceIn(3, 6) - 1),
            mineOnlyWhileCharging = o.optBoolean("mineOnlyWhileCharging",true),
            pauseBelowBatteryPercent = o.optInt("pauseBelowBatteryPercent",30).coerceIn(0,100), pauseWhenScreenOn = o.optBoolean("pauseWhenScreenOn"),
            wifiOnly = o.optBoolean("wifiOnly"),
        )
    }.getOrDefault(TuningSettings())
}
