package com.thecryptosteer.xmrminer

import android.app.Application
import com.thecryptosteer.xmrminer.data.AppContainer

class CryptoSteerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (BuildConfig.INSTALLATION_TELEMETRY_ENABLED) {
            container.installationTelemetry.start()
        }
    }
}
