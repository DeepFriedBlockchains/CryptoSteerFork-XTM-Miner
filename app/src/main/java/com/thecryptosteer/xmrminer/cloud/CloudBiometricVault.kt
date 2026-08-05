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

class CloudBiometricVault(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_biometric_vault", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "cryptosteer_cloud_biometric_v1"
    fun hasSession(): Boolean = prefs.contains("ciphertext") && prefs.contains("iv")
    fun encryptionCipher(): Cipher {
        if (!store.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build())
                generateKey()
            }
        }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    }
    fun store(cipher: Cipher, raw: String) {
        val encrypted = cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
    fun decryptionCipher(): Cipher {
        val iv = Base64.decode(requireNotNull(prefs.getString("iv", null)), Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
    }
    fun decrypt(cipher: Cipher): String = cipher.doFinal(Base64.decode(requireNotNull(prefs.getString("ciphertext", null)), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    fun clear() { prefs.edit().clear().apply(); if (store.containsAlias(alias)) store.deleteEntry(alias) }
    private fun key(): SecretKey = store.getKey(alias, null) as SecretKey
}
