package com.thecryptosteer.xmrminer.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UpdateManager.ACTION_INSTALL_RESULT) UpdateManager.dispatch(intent)
    }
}
