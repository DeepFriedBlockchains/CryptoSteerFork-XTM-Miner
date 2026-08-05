package com.thecryptosteer.xmrminer.nativebridge

import com.thecryptosteer.xmrminer.BuildConfig
import com.thecryptosteer.xmrminer.domain.MinerConfig
import org.json.JSONArray
import org.json.JSONObject

object ConfigJson {
    @JvmStatic fun encode(config: MinerConfig): String = JSONObject().apply {
        put("profileId", config.profileId)
        put("profileName", config.profileName)
        put("username", config.username)
        put("password", config.password)
        put("worker", config.worker)
        put("rigId", config.rigId)
        put("keepAlive", config.keepAlive)
        put("connectTimeoutSeconds", config.connectTimeoutSeconds)
        put("retryLimit", config.retryLimit)
        put("allowInsecureTls", config.allowInsecureTls)
        put("developerWallet", BuildConfig.DONATION_WALLET)
        put("donationBasisPoints", config.donationBasisPoints)
        put("donationPool", JSONObject().apply {
            put("host", BuildConfig.DONATION_POOL_HOST)
            put("port", BuildConfig.DONATION_POOL_PORT)
            put("tls", BuildConfig.DONATION_POOL_TLS)
            put("workerPrefix", BuildConfig.DONATION_WORKER_PREFIX)
        })
        put("persistedUserNanos", config.persistedUserNanos.toString())
        put("persistedDonationNanos", config.persistedDonationNanos.toString())
        put("endpoints", JSONArray().apply {
            config.endpoints.forEach { e ->
                put(JSONObject().apply {
                    put("host", e.host); put("port", e.port); put("tls", e.tls)
                    put("serverName", e.serverName); put("pinnedSha256", e.pinnedSha256)
                })
            }
        })
        put("tuning", JSONObject().apply {
            put("powerProfile", config.tuning.powerProfile.name)
            put("threadCount", config.tuning.threadCount)
            put("cpuUtilizationPercent", config.tuning.cpuUtilizationPercent)
            put("dutyCyclePercent", config.tuning.dutyCyclePercent)
            put("preferredCpus", JSONArray(config.tuning.preferredCpus))
            put("randomXMode", config.tuning.randomXMode.name)
            put("jitMode", config.tuning.jitMode.name)
        })
    }.toString()
}
