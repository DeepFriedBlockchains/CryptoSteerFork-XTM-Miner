package com.thecryptosteer.xmrminer.cloud

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.thecryptosteer.xmrminer.CryptoSteerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class DeviceApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        if (deviceId.isBlank()) return
        val approve = when (intent.action) {
            ACTION_APPROVE -> true
            ACTION_REJECT -> false
            else -> return
        }
        context.getSystemService(NotificationManager::class.java).cancel(deviceId.hashCode())
        val pending = goAsync()
        val manager = (context.applicationContext as CryptoSteerApplication).container.cloudControl
        val job = manager.approveDevice(deviceId, approve)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            joinAll(job)
            pending.finish()
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.thecryptosteer.xmrminer.action.APPROVE_ACCOUNT_DEVICE"
        const val ACTION_REJECT = "com.thecryptosteer.xmrminer.action.REJECT_ACCOUNT_DEVICE"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
