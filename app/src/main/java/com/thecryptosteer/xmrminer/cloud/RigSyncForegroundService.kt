package com.thecryptosteer.xmrminer.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.thecryptosteer.xmrminer.CryptoSteerApplication
import com.thecryptosteer.xmrminer.R

class RigSyncForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channelId = "cryptosteer_remote_control"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "Remote rig control", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CryptoSteer remote control active")
            .setContentText("Live telemetry, device approvals, and account-owned rig commands are connected.")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        startForeground(REMOTE_CONTROL_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        (application as CryptoSteerApplication).container.cloudControl.activate()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as CryptoSteerApplication).container.cloudControl.activate()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val ACTION_START = "com.thecryptosteer.xmrminer.action.START_REMOTE_CONTROL"; private const val REMOTE_CONTROL_NOTIFICATION_ID = 9042 }
}
