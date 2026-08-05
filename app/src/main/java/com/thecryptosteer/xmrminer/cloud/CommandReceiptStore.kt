package com.thecryptosteer.xmrminer.cloud

import android.content.Context
import org.json.JSONObject

/**
 * Persists final command results so a server retry after a disconnect is
 * acknowledged without applying the same state-changing action twice.
 */
class CommandReceiptStore(context: Context) {
    private val preferences = context.getSharedPreferences("remote_command_receipts", Context.MODE_PRIVATE)

    @Synchronized
    fun get(commandId: String): CommandReceipt? {
        val raw = preferences.getString(commandId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CommandReceipt(
                success = json.getBoolean("success"),
                result = JSONObject(json.getString("result")),
                completedAtMillis = json.getLong("completedAtMillis"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun put(commandId: String, success: Boolean, result: JSONObject) {
        val now = System.currentTimeMillis()
        preferences.edit()
            .putString(
                commandId,
                JSONObject()
                    .put("success", success)
                    .put("result", result.toString())
                    .put("completedAtMillis", now)
                    .toString(),
            )
            .apply()
        trim(now)
    }

    @Synchronized
    private fun trim(now: Long) {
        val maximumAgeMillis = 7L * 24L * 60L * 60L * 1_000L
        val entries = preferences.all.mapNotNull { (key, value) ->
            val completed = runCatching { JSONObject(value as String).getLong("completedAtMillis") }.getOrNull()
            completed?.let { Triple(key, it, value) }
        }.sortedByDescending { it.second }
        val editor = preferences.edit()
        entries.forEachIndexed { index, entry ->
            if (index >= MAX_RECEIPTS || now - entry.second > maximumAgeMillis) editor.remove(entry.first)
        }
        editor.apply()
    }

    data class CommandReceipt(
        val success: Boolean,
        val result: JSONObject,
        val completedAtMillis: Long,
    )

    private companion object {
        const val MAX_RECEIPTS = 500
    }
}
