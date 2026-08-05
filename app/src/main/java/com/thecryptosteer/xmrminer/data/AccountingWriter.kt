package com.thecryptosteer.xmrminer.data

import android.content.Context
import com.thecryptosteer.xmrminer.CryptoSteerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AccountingWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic fun persist(context: Context, userNanos: Long, donationNanos: Long) {
        val app = context.applicationContext as CryptoSteerApplication
        scope.launch { app.container.repository.persistAccounting(userNanos, donationNanos) }
    }
}
