package com.thecryptosteer.xmrminer.telemetry

import android.content.Context
import android.os.Build
import android.util.Base64
import com.thecryptosteer.xmrminer.BuildConfig
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

class InstallationIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("installation_identity_v1", Context.MODE_PRIVATE)

    val clientInstallationId: String = preferences.getString("client_installation_id", null)
        ?: UUID.randomUUID().toString().also { preferences.edit().putString("client_installation_id", it).commit() }

    val secret: String = preferences.getString("installation_secret", null)
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.let { bytes ->
            Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).also {
                preferences.edit().putString("installation_secret", it).commit()
            }
        }

    fun registrationJson(): org.json.JSONObject = org.json.JSONObject()
        .put("clientInstallationId", clientInstallationId)
        .put("installationSecret", secret)
        .put("displayName", listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ").ifBlank { "Android device" })
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("androidVersion", Build.VERSION.RELEASE)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("appVersionCode", BuildConfig.VERSION_CODE)
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        .put("locale", Locale.getDefault().toLanguageTag())
}
