package com.thecryptosteer.xmrminer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thecryptosteer.xmrminer.cloud.UpdateUiState

@Composable
fun UpdateCheckingScreen(state: UpdateUiState) {
    MonitoringBackdrop {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(Modifier.padding(24.dp).fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator()
                    Text("Verifying release channel", style = MaterialTheme.typography.headlineSmall)
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun UpdateRequiredScreen(
    state: UpdateUiState,
    canInstallPackages: Boolean,
    onUpdate: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val manifest = state.manifest
    MonitoringBackdrop {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(Modifier.padding(20.dp).fillMaxWidth().monitoringPanel(MaterialTheme.colorScheme.primary)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.Security, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Required signed update", style = MaterialTheme.typography.headlineMedium)
                    Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (manifest != null) {
                        DefinitionRow("Required version", "${manifest.versionName} (${manifest.versionCode})")
                        DefinitionRow("Download", formatBytes(manifest.fileSize))
                        if (manifest.releaseNotes.isNotBlank()) Text(manifest.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.progress > 0f && state.progress < 1f) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.progress * 100).toInt()}% downloaded", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.error.isNotBlank()) InlineBanner("Update error", state.error, StatusTone.CRITICAL)
                    Button(
                        onClick = if (canInstallPackages) onUpdate else onOpenInstallPermission,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.installing,
                    ) {
                        Icon(Icons.Outlined.Download, null)
                        Text(if (canInstallPackages) "Download and update" else "Allow update installation")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetry, enabled = !state.installing) {
                            Icon(Icons.Outlined.Refresh, null)
                            Text("Check again")
                        }
                    }
                    Text("The APK is downloaded inside CryptoSteer, checked against the signed hash and release manifest, then handed to Android’s protected package installer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
