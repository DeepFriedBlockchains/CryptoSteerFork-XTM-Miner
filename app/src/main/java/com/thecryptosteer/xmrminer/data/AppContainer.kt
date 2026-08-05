package com.thecryptosteer.xmrminer.data

import android.content.Context
import com.thecryptosteer.xmrminer.security.SecretStore
import com.thecryptosteer.xmrminer.cloud.CloudControlManager
import com.thecryptosteer.xmrminer.update.UpdateManager
import com.thecryptosteer.xmrminer.telemetry.InstallationTelemetryManager

class AppContainer(context: Context) {
    val preferences = AppPreferences(context.applicationContext)
    val secrets = SecretStore()
    val donationPolicy = DonationPolicyStore(context.applicationContext)
    val repository = MiningRepository(context.applicationContext, preferences, secrets, donationPolicy)
    val installationTelemetry = InstallationTelemetryManager(context.applicationContext, repository)
    val cloudControl = CloudControlManager(context.applicationContext, repository, donationPolicy, installationTelemetry)
    val updateManager = UpdateManager(context.applicationContext)
}
