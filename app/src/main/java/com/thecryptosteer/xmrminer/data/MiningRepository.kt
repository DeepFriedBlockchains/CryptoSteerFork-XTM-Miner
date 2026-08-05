package com.thecryptosteer.xmrminer.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.MinerConfig
import com.thecryptosteer.xmrminer.domain.PoolProfile
import com.thecryptosteer.xmrminer.domain.PoolValidator
import com.thecryptosteer.xmrminer.security.SecretStore
import com.thecryptosteer.xmrminer.service.MiningForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MiningRepository(
    private val context: Context,
    private val preferences: AppPreferences,
    private val secrets: SecretStore,
    private val donationPolicy: DonationPolicyStore,
) {
    val settings: Flow<AppSettings> = preferences.settings
    val profiles: Flow<List<PoolProfile>> = preferences.profiles
    val accounting: Flow<Pair<Long, Long>> = preferences.accounting
    val snapshot = MiningStateBus.snapshot
    val logs = MiningStateBus.logs
    val history = MiningStateBus.history
    val benchmarkJson = preferences.benchmarkJson
    val autoTuneJson = preferences.autoTuneJson

    suspend fun saveProfile(profile: PoolProfile, plainPassword: String?) {
        val normalizedName = profile.name.trim().ifBlank { profile.primary.host }
        val candidate = profile.copy(name = normalizedName)
        val result = PoolValidator.validate(candidate)
        require(result.valid) { result.errors.joinToString("\n") }
        val all = profiles.first().toMutableList()
        val index = all.indexOfFirst { it.id == candidate.id }
        val existingEncrypted = all.getOrNull(index)?.encryptedPassword.orEmpty()
        val encrypted = when {
            plainPassword != null -> secrets.encrypt(plainPassword)
            candidate.encryptedPassword.isNotBlank() -> candidate.encryptedPassword
            else -> existingEncrypted
        }
        val normalized = candidate.copy(encryptedPassword = encrypted)
        if (normalized.defaultProfile) {
            for (i in all.indices) all[i] = all[i].copy(defaultProfile = false)
        }
        if (index >= 0) all[index] = normalized else all += normalized
        if (all.none { it.defaultProfile } && all.isNotEmpty()) all[0] = all[0].copy(defaultProfile = true)
        preferences.saveProfiles(all)
    }

    suspend fun deleteProfile(id: String) {
        val updated = profiles.first().filterNot { it.id == id }.toMutableList()
        if (updated.isNotEmpty() && updated.none { it.defaultProfile }) updated[0] = updated[0].copy(defaultProfile = true)
        preferences.saveProfiles(updated)
    }

    suspend fun duplicateProfile(id: String) {
        val source = profiles.first().first { it.id == id }
        saveProfile(source.copy(id = java.util.UUID.randomUUID().toString(), name = "${source.name} Copy", defaultProfile = false), null)
    }

    suspend fun testProfile(id: String): PoolConnectionTester.Result = withContext(Dispatchers.IO) {
        val profile = profiles.first().first { it.id == id }
        PoolConnectionTester.test(
            profile.primary,
            profile.username,
            effectivePassword(profile),
            profile.worker,
            profile.connectTimeoutSeconds,
            profile.allowInsecureTls,
        )
    }

    suspend fun fetchLiveJob(id: String): PoolConnectionTester.LiveJob = withContext(Dispatchers.IO) {
        val profile = profiles.first().first { it.id == id && it.enabled }
        PoolConnectionTester.fetchLiveJob(
            profile.primary,
            profile.username,
            effectivePassword(profile),
            profile.worker,
            profile.connectTimeoutSeconds,
            profile.allowInsecureTls,
        )
    }


    suspend fun buildAutoTuneConfig(profileId: String, durationSeconds: Int): String = withContext(Dispatchers.IO) {
        val profile = profiles.first().firstOrNull { it.id == profileId && it.enabled }
            ?: error("The selected pool profile is unavailable.")
        JSONObject().apply {
            put("durationSeconds", durationSeconds.coerceIn(60, 600))
            put("username", profile.username)
            put("password", effectivePassword(profile))
            put("worker", profile.worker)
            put("rigId", profile.rigId.ifBlank { profile.worker })
            put("connectTimeoutSeconds", profile.connectTimeoutSeconds)
            put("retryLimit", profile.retryLimit)
            put("allowInsecureTls", profile.allowInsecureTls)
            put("endpoints", JSONArray().apply {
                (listOf(profile.primary) + profile.failovers).forEach { endpoint ->
                    put(JSONObject().apply {
                        put("host", endpoint.host)
                        put("port", endpoint.port)
                        put("tls", endpoint.tls)
                        put("serverName", endpoint.serverName)
                        put("pinnedSha256", endpoint.pinnedSha256)
                    })
                }
            })
        }.toString()
    }

    suspend fun saveSettings(value: AppSettings) = preferences.saveSettings(value)
    suspend fun acceptDisclosure() = preferences.acceptDisclosure()

    suspend fun start(profileId: String, settingsOverride: AppSettings? = null) {
        val settings = settingsOverride ?: settings.first()
        require(settings.disclosureAccepted) { "The first-launch disclosure must be accepted before mining." }
        val profile = profiles.first().firstOrNull { it.id == profileId && it.enabled } ?: error("The selected pool profile is unavailable.")
        val account = accounting.first()
        val config = MinerConfig(
            profileId = profile.id,
            profileName = profile.name,
            endpoints = listOf(profile.primary) + profile.failovers,
            username = profile.username,
            password = effectivePassword(profile),
            worker = profile.worker,
            rigId = profile.rigId.ifBlank { profile.worker },
            keepAlive = profile.keepAlive,
            connectTimeoutSeconds = profile.connectTimeoutSeconds,
            retryLimit = profile.retryLimit,
            allowInsecureTls = profile.allowInsecureTls,
            tuning = settings.tuning,
            persistedUserNanos = account.first,
            persistedDonationNanos = account.second,
            donationBasisPoints = donationPolicy.currentBasisPoints,
        )
        MiningForegroundService.pendingConfig = config
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MiningForegroundService::class.java).setAction(MiningForegroundService.ACTION_START),
            )
        } catch (exception: RuntimeException) {
            MiningForegroundService.pendingConfig = null
            throw exception
        }
    }

    fun pause() = context.startService(Intent(context, MiningForegroundService::class.java).setAction(MiningForegroundService.ACTION_PAUSE))
    fun resume() = context.startService(Intent(context, MiningForegroundService::class.java).setAction(MiningForegroundService.ACTION_RESUME))
    fun stop() = context.startService(Intent(context, MiningForegroundService::class.java).setAction(MiningForegroundService.ACTION_STOP))
    suspend fun persistAccounting(user: Long, donation: Long) = preferences.saveAccounting(user, donation)
    suspend fun saveBenchmarkJson(json: String) = preferences.saveBenchmarkJson(json)
    suspend fun saveAutoTuneJson(json: String) = preferences.saveAutoTuneJson(json)

    private fun effectivePassword(profile: PoolProfile): String {
        val explicit = secrets.decrypt(profile.encryptedPassword)
        if (profile.useWorkerAsPassword) return profile.worker.ifBlank { "x" }
        return explicit.ifBlank { profile.worker.ifBlank { "x" } }
    }
}
