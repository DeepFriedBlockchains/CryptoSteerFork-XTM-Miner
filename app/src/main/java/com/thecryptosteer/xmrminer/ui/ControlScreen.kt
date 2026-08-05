package com.thecryptosteer.xmrminer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thecryptosteer.xmrminer.cloud.ControlUiState
import com.thecryptosteer.xmrminer.cloud.RigSummary

@Composable
fun ControlScreen(
    state: ControlUiState,
    onRegister: (String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onRecover: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onSelectRig: (String?) -> Unit,
    onApproveDevice: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onEnableBiometric: () -> Unit,
    onSkipBiometric: () -> Unit,
    onUnlockBiometric: () -> Unit,
) {
    val account = state.account
    if (account.session == null) {
        AccountAccessScreen(state, onRegister, onLogin, onRecover, onUnlockBiometric)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Rig control",
                subtitle = "Near-live status and authenticated control for every approved device on this wallet account.",
                action = {
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, null)
                        Text("Refresh")
                    }
                },
            )
        }
        if (account.biometricOffer) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().monitoringPanel(MaterialTheme.colorScheme.primary),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Enable fingerprint or face unlock", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Your refresh session is moved into an Android Keystore key that requires strong biometric authentication for every unlock.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onEnableBiometric) { Text("Enable") }
                            OutlinedButton(onClick = onSkipBiometric) { Text("Not now") }
                        }
                    }
                }
            }
        }
        if (account.error.isNotBlank()) item { InlineBanner("Account error", account.error, StatusTone.CRITICAL) }
        if (account.message.isNotBlank()) item { InlineBanner("Account", account.message, StatusTone.LIVE) }
        if (state.commandMessage.isNotBlank()) item { InlineBanner("Remote command", state.commandMessage, StatusTone.ACTION) }

        if (state.pendingApprovals.isNotEmpty()) {
            item { SectionHeader("Device approval requests", "Only approve a device you recognize and physically authorized.") }
            items(state.pendingApprovals, key = { it.deviceId }) { request ->
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(request.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${request.platform} • ${request.appVersion}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onApproveDevice(request.deviceId, true) }) { Text("Approve") }
                            OutlinedButton(onClick = { onApproveDevice(request.deviceId, false) }) { Text("Reject") }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Approved rigs", "Selecting a rig makes the rest of the app display and control that device. Choose This device to return to local control.") }
        if (state.rigs.isEmpty()) {
            item { EmptyState(
                    title = "No rig status yet",
                    message = "The current device will appear after its first authenticated telemetry heartbeat.",
                    icon = Icons.Outlined.DevicesOther,
                ) }
        } else {
            items(state.rigs, key = { it.deviceId }) { rig ->
                RigCard(rig, selected = if (rig.currentDevice) state.selectedRigId == null else state.selectedRigId == rig.deviceId) {
                    onSelectRig(if (rig.currentDevice) null else rig.deviceId)
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader("Account security")
                    DefinitionRow("Wallet", account.session.walletAddress)
                    DefinitionRow("Trusted device", account.session.deviceId)
                    Text("Lost trusted device or forgotten password: contact dev@thecryptosteer.com. Recovery actions are audited in the private Command Center.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onLogout) {
                        Icon(Icons.Outlined.Logout, null)
                        Text("Sign out")
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountAccessScreen(
    state: ControlUiState,
    onRegister: (String, String) -> Unit,
    onLogin: (String, String) -> Unit,
    onRecover: (String, String, String) -> Unit,
    onUnlockBiometric: () -> Unit,
) {
    var wallet by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var recoveryCode by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("login") }
    val account = state.account
    val recovering = mode == "recover"
    val creating = mode == "create"
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Control account", "Mining remains available without an account. Sign in only when you want cross-device rig status and control.") }
        if (account.biometricLockedSession) {
            item {
                Card(Modifier.fillMaxWidth().monitoringPanel(MaterialTheme.colorScheme.primary)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Biometric session available", style = MaterialTheme.typography.titleLarge)
                        Text("Unlock the locally protected session without re-entering the wallet password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onUnlockBiometric) { Text("Unlock with biometrics") }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (recovering) Icons.Outlined.Key else Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Text(when { recovering -> "Recover account"; creating -> "Create wallet account"; else -> "Sign in" }, style = MaterialTheme.typography.titleLarge)
                    }
                    if (recovering) {
                        Text("Contact dev@thecryptosteer.com first. After ownership is verified, enter the one-time support code here and choose a new password. All older sessions stay revoked.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = wallet,
                        onValueChange = { wallet = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Monero wallet address") },
                        singleLine = true,
                    )
                    if (recovering) {
                        OutlinedTextField(
                            value = recoveryCode,
                            onValueChange = { recoveryCode = it.uppercase() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("One-time support recovery code") },
                            supportingText = { Text("Codes expire 30 minutes after they are created in Command Center.") },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (recovering) "New password — minimum 12 characters" else "Password — minimum 12 characters") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (recovering) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Confirm new password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                        )
                    }
                    Button(
                        onClick = {
                            when {
                                recovering -> onRecover(wallet, recoveryCode, password)
                                creating -> onRegister(wallet, password)
                                else -> onLogin(wallet, password)
                            }
                        },
                        enabled = !account.loading && wallet.isNotBlank() && password.length >= 12 && (!recovering || recoveryCode.isNotBlank() && confirmPassword == password),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (account.loading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                        Text(when { recovering -> "Recover and trust this device"; creating -> "Create account"; else -> "Sign in" })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { mode = "login" }) { Text("Sign in") }
                        TextButton(onClick = { mode = "create" }) { Text("Create account") }
                        TextButton(onClick = { mode = "recover" }) { Text("Forgot access") }
                    }
                }
            }
        }
        if (account.pendingLogin != null) {
            item { InlineBanner("Approval required", "Approve this device from a previously trusted rig. This screen checks automatically. If access is lost, contact dev@thecryptosteer.com.", StatusTone.ACTION) }
        }
        if (account.error.isNotBlank()) item { InlineBanner("Unable to sign in", account.error, StatusTone.CRITICAL) }
        if (account.message.isNotBlank()) item { InlineBanner("Account", account.message, StatusTone.LIVE) }
    }
}

@Composable
private fun RigCard(rig: RigSummary, selected: Boolean, onClick: () -> Unit) {
    val tone = if (rig.online) StatusTone.LIVE else StatusTone.NEUTRAL
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (rig.online) Icons.Outlined.Computer else Icons.Outlined.CloudOff, null, tint = if (rig.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        Text(if (rig.currentDevice) "${rig.displayName} — This device" else rig.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${rig.platform} • ${rig.appVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (rig.online) "Online" else "Offline", tone)
                StatusChip(rig.state, if (rig.mining) StatusTone.LIVE else StatusTone.NEUTRAL)
                StatusChip(formatHashrate(rig.hashrate), StatusTone.ACTION)
            }
        }
    }
}

private fun formatHashrate(hashrate: Double): String {
    val safeHashrate = hashrate.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    return when {
        safeHashrate >= 1_000_000.0 -> "%.2f MH/s".format(java.util.Locale.US, safeHashrate / 1_000_000.0)
        safeHashrate >= 1_000.0 -> "%.2f kH/s".format(java.util.Locale.US, safeHashrate / 1_000.0)
        else -> "%.1f H/s".format(java.util.Locale.US, safeHashrate)
    }
}
