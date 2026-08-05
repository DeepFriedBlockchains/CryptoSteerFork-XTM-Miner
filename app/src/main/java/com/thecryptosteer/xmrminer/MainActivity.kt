package com.thecryptosteer.xmrminer

import android.Manifest
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.thecryptosteer.xmrminer.ui.CryptoSteerApp
import com.thecryptosteer.xmrminer.ui.CryptoSteerTheme
import com.thecryptosteer.xmrminer.ui.MainViewModel

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var foregroundUpdateCheckJob: Job? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.canInstallPackages()) viewModel.installUpdate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            CryptoSteerTheme {
                CryptoSteerApp(
                    viewModel = viewModel,
                    onEnableBiometric = ::enableBiometric,
                    onUnlockBiometric = ::unlockBiometric,
                    onOpenInstallPermission = { installPermission.launch(viewModel.unknownSourcesIntent()) },
                )
            }
        }
    }


    override fun onStart() {
        super.onStart()
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        viewModel.checkForUpdates()
        foregroundUpdateCheckJob?.cancel()
        foregroundUpdateCheckJob = lifecycleScope.launch {
            while (isActive) {
                delay(60_000L)
                viewModel.checkForUpdates()
            }
        }
    }

    override fun onStop() {
        foregroundUpdateCheckJob?.cancel()
        foregroundUpdateCheckJob = null
        super.onStop()
    }

    private fun enableBiometric() {
        runCatching { viewModel.biometricEnrollmentCipher() }
            .onSuccess { cipher ->
                biometricPrompt(
                    title = "Protect CryptoSteer Control",
                    subtitle = "Authenticate to store this wallet account session",
                    cipher = cipher,
                    onSuccess = viewModel::completeBiometricEnrollment,
                )
            }
            .onFailure { viewModel.biometricError(it.message ?: "Strong biometric authentication is unavailable.") }
    }

    private fun unlockBiometric() {
        runCatching { viewModel.biometricUnlockCipher() }
            .onSuccess { cipher ->
                biometricPrompt(
                    title = "Unlock CryptoSteer Control",
                    subtitle = "Authenticate to restore your protected session",
                    cipher = cipher,
                    onSuccess = viewModel::completeBiometricUnlock,
                )
            }
            .onFailure { viewModel.biometricError(it.message ?: "The protected session is unavailable.") }
    }

    private fun biometricPrompt(
        title: String,
        subtitle: String,
        cipher: javax.crypto.Cipher,
        onSuccess: (javax.crypto.Cipher) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(onSuccess)
                        ?: viewModel.biometricError("Android returned no biometric cryptographic result.")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.biometricError(errString.toString())
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
