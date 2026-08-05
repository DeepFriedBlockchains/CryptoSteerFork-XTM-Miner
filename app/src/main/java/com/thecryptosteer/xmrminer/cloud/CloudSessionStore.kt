package com.thecryptosteer.xmrminer.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class CloudSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("cloud_session_store", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "cryptosteer_cloud_session_v1"

    fun save(session: CloudSession) {
        ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val raw = JSONObject()
            .put("accessToken", session.accessToken)
            .put("accessExpiresAt", session.accessExpiresAt)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("walletAddress", session.walletAddress)
            .toString()
        val encrypted = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(): CloudSession? = runCatching {
        val encrypted = Base64.decode(preferences.getString("ciphertext", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(preferences.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val o = JSONObject(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        CloudSession(o.getString("accessToken"), o.getString("accessExpiresAt"), o.getString("refreshToken"), o.getString("userId"), o.getString("deviceId"), o.getString("walletAddress"))
    }.getOrNull()

    fun clear() = preferences.edit().clear().apply()

    private fun ensureKey() {
        if (keyStore.containsAlias(alias)) return
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
    private fun key(): SecretKey = keyStore.getKey(alias, null) as SecretKey
}
