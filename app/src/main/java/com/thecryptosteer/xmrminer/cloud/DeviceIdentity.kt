package com.thecryptosteer.xmrminer.cloud

import android.content.Context
import android.os.Build
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("cloud_device_identity", Context.MODE_PRIVATE)
    val clientDeviceId: String = preferences.getString("client_device_id", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("client_device_id", it).commit()
    }
    val displayName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android rig" }
}
