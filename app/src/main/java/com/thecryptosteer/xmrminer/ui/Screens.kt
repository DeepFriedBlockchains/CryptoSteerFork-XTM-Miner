package com.thecryptosteer.xmrminer.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thecryptosteer.xmrminer.BuildConfig
import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.AutoTunePhase
import com.thecryptosteer.xmrminer.domain.AutoTuneUiState
import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.JitMode
import com.thecryptosteer.xmrminer.domain.LogEntry
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.MiningState
import com.thecryptosteer.xmrminer.domain.PoolEndpoint
import com.thecryptosteer.xmrminer.domain.PoolProfile
import com.thecryptosteer.xmrminer.domain.PoolValidator
import com.thecryptosteer.xmrminer.domain.PowerProfile
import com.thecryptosteer.xmrminer.domain.RandomXMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private val pagePadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

@Composable
fun MiningDetailsScreen(
    snapshot: MiningSnapshot,
    logs: List<LogEntry>,
    onOpenLogs: () -> Unit,
) {
    val fastest = snapshot.threadHashrates.maxOrNull() ?: 0.0
    val donationFailure = logs.lastOrNull { it.message.contains("Donation pool connection failed", ignoreCase = true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Active session",
                subtitle = humanMiningSummary(snapshot.state, snapshot.statusMessage),
                action = { LiveDataChip(snapshot.updatedAtMillis) },
            )
        }
        if (snapshot.state == MiningState.STOPPED && snapshot.uptimeSeconds == 0L) {
            item {
                EmptyState(
                    title = "No active mining session",
                    message = "Start mining from the Dashboard to see pool, engine, donation, and per-worker details.",
                    icon = Icons.Outlined.Memory,
                )
            }
        } else {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionHeader("Session overview")
                        DefinitionRow("State", humanMiningSummary(snapshot.state, snapshot.statusMessage), tone = stateTone(snapshot.state))
                        DefinitionRow("Current hashrate", "%.2f H/s".format(Locale.US, snapshot.hashrate), tone = StatusTone.LIVE)
                        DefinitionRow("Average hashrate", "%.2f H/s".format(Locale.US, snapshot.averageHashrate))
                        DefinitionRow("Uptime", formatDuration(snapshot.uptimeSeconds))
                        DefinitionRow("Accepted shares", snapshot.accepted.toString(), tone = StatusTone.HEALTHY)
                        DefinitionRow("Rejected shares", snapshot.rejected.toString(), tone = if (snapshot.rejected > 0) StatusTone.WARNING else StatusTone.NEUTRAL)
                        DefinitionRow("Stale shares", snapshot.stale.toString())
                    }
                }
            }
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionHeader("Pool connection", "Live Stratum connection state")
                        DefinitionRow("Endpoint", snapshot.activePool.ifBlank { "Not connected" })
                        DefinitionRow("Transport", if (snapshot.poolConnected) "Connected" else "Disconnected", tone = if (snapshot.poolConnected) StatusTone.HEALTHY else StatusTone.WARNING)
                        DefinitionRow("Pool job", if (snapshot.jobReceived) "Active" else "Waiting", tone = if (snapshot.jobReceived) StatusTone.LIVE else StatusTone.NEUTRAL)
                        DefinitionRow("Latency", if (snapshot.poolLatencyMillis > 0L) "${snapshot.poolLatencyMillis} ms" else "Not measured")
                        DefinitionRow("Last job", formatTimestamp(snapshot.lastJobAtMillis))
                        DefinitionRow("Last accepted share", formatTimestamp(snapshot.lastAcceptedAtMillis))
                    }
                }
            }
            item {
                ExpandableTechnicalDetails(
                    title = "RandomX engine",
                    summary = "${snapshot.randomXMode.ifBlank { "Not initialized" }} • ${snapshot.workers} workers",
                    initiallyExpanded = true,
                ) {
                    DefinitionRow("Requested memory", snapshot.requestedRandomXMode.ifBlank { "Not initialized" })
                    DefinitionRow("Effective memory", snapshot.randomXMode.ifBlank { "Not initialized" })
                    DefinitionRow("Dataset", if (snapshot.datasetProgress >= 100) "Ready" else "${snapshot.datasetProgress}% prepared")
                    DefinitionRow("JIT", if (snapshot.jitEnabled) "Enabled" else "Disabled or unavailable")
                    DefinitionRow("Hardware AES", if (snapshot.hardwareAes) "Available" else "Unavailable")
                    DefinitionRow("Runtime page size", runtimePageSizeLabel(snapshot.pageSize))
                    DefinitionRow("Active workers", snapshot.workers.toString())
                }
            }
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionHeader("Donation accounting", "2.00% initially; 1.00% after 30 days of signed-in account mining")
                        DefinitionRow("Actual session percentage", "%.6f%%".format(Locale.US, snapshot.donationPercent))
                        DefinitionRow("User mining time", formatNanos(snapshot.userMiningNanos))
                        DefinitionRow("Donation mining time", formatNanos(snapshot.donationMiningNanos))
                        DefinitionRow("Connection", if (snapshot.state == MiningState.MINING_DONATION) "Donation mining" else if (donationFailure == null) "Idle" else "Last connection failed", tone = if (donationFailure == null) StatusTone.NEUTRAL else StatusTone.WARNING)
                    }
                }
            }
            if (donationFailure != null) {
                item {
                    InlineWarningBanner(
                        title = "Donation connection issue",
                        message = "User mining continues and the fee is not increased to compensate. ${donationFailure.message}",
                        actionLabel = "Open matching logs",
                        onAction = onOpenLogs,
                    )
                }
            }
            if (snapshot.lastError.isNotBlank()) {
                item {
                    InlineBanner(
                        title = if (snapshot.state == MiningState.ERROR) "Active error" else "Most recent recovered error",
                        message = snapshot.lastError,
                        tone = if (snapshot.state == MiningState.ERROR) StatusTone.CRITICAL else StatusTone.WARNING,
                        actionLabel = "View logs",
                        onAction = onOpenLogs,
                    )
                }
            } else {
                item { SuccessBanner("No active errors", "The current session has no unresolved engine or pool error.") }
            }
            item {
                ExpandableTechnicalDetails(
                    title = "Per-thread performance",
                    summary = "${snapshot.threadHashrates.size} workers",
                    initiallyExpanded = true,
                ) {
                    if (snapshot.threadHashrates.isEmpty()) {
                        Text("Worker measurements appear after RandomX starts hashing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        snapshot.threadHashrates.forEachIndexed { index, rate ->
                            PerThreadPerformanceBar(
                                worker = index,
                                cpu = snapshot.cpuAssignments.getOrNull(index),
                                currentHashrate = rate,
                                fastestHashrate = fastest,
                                paused = snapshot.state == MiningState.PAUSED,
                            )
                            if (index != snapshot.threadHashrates.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PoolProfilesScreen(profiles: List<PoolProfile>, viewModel: MainViewModel) {
    var editing by remember { mutableStateOf<PoolProfile?>(null) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var importText by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<PoolProfile?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "Pool profiles",
                subtitle = "Add a pool host, port, wallet, and worker name. The worker is automatically sent as the password and rig ID.",
                action = {
                    PrimaryActionButton(
                        label = "Add",
                        icon = Icons.Outlined.Add,
                        onClick = {
                            editing = PoolProfile(
                                name = "",
                                primary = PoolEndpoint("", 10128, false),
                                username = "",
                                defaultProfile = profiles.isEmpty(),
                            )
                        },
                    )
                },
            )
        }
        status?.let { result ->
            item {
                if (result.first) SuccessBanner("Pool profile", result.second)
                else InlineBanner("Pool profile needs attention", result.second, StatusTone.CRITICAL)
            }
        }
        if (profiles.isEmpty()) {
            item {
                EmptyState(
                    title = "No pool profiles",
                    message = "Create a pool profile before starting mining or running live auto-tune.",
                    icon = Icons.Outlined.Public,
                    actionLabel = "Add pool",
                    onAction = {
                        editing = PoolProfile(
                            name = "",
                            primary = PoolEndpoint("", 10128, false),
                            username = "",
                            defaultProfile = true,
                        )
                    },
                )
            }
        } else {
            items(profiles, key = { it.id }) { profile ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (profile.defaultProfile && profile.enabled) CryptoSteerThemeTokens.colors.selected else MaterialTheme.colorScheme.surface,
                    ),
                    border = BorderStroke(
                        if (profile.defaultProfile && profile.enabled) 1.5.dp else 1.dp,
                        if (profile.defaultProfile && profile.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f) else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                                Text("${profile.primary.host}:${profile.primary.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(if (profile.primary.tls) "TLS" else "TCP", if (profile.primary.tls) StatusTone.LIVE else StatusTone.NEUTRAL)
                                if (profile.defaultProfile) StatusChip("Default", StatusTone.ACTION)
                                StatusChip(if (profile.enabled) "Enabled" else "Disabled", if (profile.enabled) StatusTone.HEALTHY else StatusTone.NEUTRAL)
                            }
                        }
                        DefinitionRow("Worker", profile.worker.ifBlank { "Not configured" })
                        DefinitionRow("Authentication", "Worker name used as password")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { editing = profile }) { Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(6.dp)); Text("Edit") }
                            OutlinedButton(onClick = {
                                status = true to "Testing ${profile.name}…"
                                viewModel.testProfile(profile.id) { result ->
                                    status = result.fold({ true to it }, { false to (it.message ?: "Connection test failed") })
                                }
                            }) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Test") }
                            IconButton(onClick = { viewModel.duplicateProfile(profile.id); status = true to "Profile duplicated." }) { Icon(Icons.Outlined.ContentCopy, "Duplicate ${profile.name}") }
                            IconButton(onClick = { deleteTarget = profile }) { Icon(Icons.Outlined.Delete, "Delete ${profile.name}", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        item {
            ExpandableTechnicalDetails(
                title = "Import or export",
                summary = "Validated CryptoSteer profile JSON",
            ) {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it.take(32_000) },
                    label = { Text("Profile JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryActionButton(
                        label = "Import",
                        icon = Icons.Outlined.Save,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { viewModel.profileFromJson(importText) }
                                .onSuccess { editing = it; status = true to "Imported into the editor. Review before saving." }
                                .onFailure { status = false to (it.message ?: "Invalid profile JSON") }
                        },
                    )
                    profiles.firstOrNull()?.let { profile ->
                        SecondaryActionButton(
                            label = "Export",
                            icon = Icons.Outlined.ContentCopy,
                            modifier = Modifier.weight(1f),
                            onClick = { importText = viewModel.profileToJson(profile, includeEncryptedCredential = false) },
                        )
                    }
                }
            }
        }
    }

    editing?.let { original ->
        PoolProfileEditorDialog(
            original = original,
            onDismiss = { editing = null },
            onSave = { profile, password ->
                viewModel.saveProfile(profile, password) { result ->
                    status = result.fold({ true to "Profile saved." }, { false to (it.message ?: "Unable to save profile") })
                    if (result.isSuccess) editing = null
                }
            },
        )
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("This removes the profile and its encrypted credential from this device.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.id)
                        status = true to "Profile deleted."
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PoolProfileEditorDialog(
    original: PoolProfile,
    onDismiss: () -> Unit,
    onSave: (PoolProfile, String?) -> Unit,
) {
    var host by remember(original.id) { mutableStateOf(original.primary.host) }
    var port by remember(original.id) { mutableStateOf(original.primary.port.toString()) }
    var wallet by remember(original.id) { mutableStateOf(original.username) }
    var worker by remember(original.id) { mutableStateOf(original.worker) }
    var profileName by remember(original.id) { mutableStateOf(original.name) }
    var tls by remember(original.id) { mutableStateOf(original.primary.tls) }
    var enabled by remember(original.id) { mutableStateOf(original.enabled) }
    var defaultProfile by remember(original.id) { mutableStateOf(original.defaultProfile) }
    var keepAlive by remember(original.id) { mutableStateOf(original.keepAlive) }
    var timeout by remember(original.id) { mutableStateOf(original.connectTimeoutSeconds.toString()) }
    var retries by remember(original.id) { mutableStateOf(original.retryLimit.toString()) }
    var failovers by remember(original.id) {
        mutableStateOf(original.failovers.joinToString("\n") { "${if (it.tls) "stratum+tls" else "stratum+tcp"}://${it.host}:${it.port}" })
    }
    var advanced by rememberSaveable(original.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.name.isBlank()) "Add pool profile" else "Edit ${original.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Only the pool host, port, wallet, and worker are required.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(host, { host = it.take(512) }, label = { Text("Pool URL or host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Pool port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(wallet, { wallet = it.trim().take(512) }, label = { Text("Wallet address or pool username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    worker,
                    { worker = it.filterNot(Char::isWhitespace).take(128) },
                    label = { Text("Worker name") },
                    supportingText = { Text("Used automatically as the pool password and rig ID.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced options" else "Show advanced options") }
                if (advanced) {
                    OutlinedTextField(profileName, { profileName = it.take(80) }, label = { Text("Profile name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    SettingSwitchRow("Use TLS", tls, { tls = it }, supportingText = "Certificate verification remains enabled for TLS pools.")
                    SettingSwitchRow("Keep connection alive", keepAlive, { keepAlive = it })
                    SettingSwitchRow("Enabled", enabled, { enabled = it })
                    SettingSwitchRow("Default profile", defaultProfile, { defaultProfile = it })
                    OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(3) }, label = { Text("Connection timeout (3–120 seconds)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(retries, { retries = it.filter(Char::isDigit).take(3) }, label = { Text("Retry limit (0–100)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(
                        failovers,
                        { failovers = it.take(8_000) },
                        label = { Text("Failover pools") },
                        supportingText = { Text("One full address per line, including scheme and port.") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val parsedHost = PoolValidator.parseHostInput(host)
                    val parsedPort = port.toIntOrNull() ?: error("Pool port is required.")
                    val endpointTls = parsedHost.tlsHint ?: tls.takeIf { advanced } ?: PoolValidator.defaultTlsForPort(parsedPort)
                    val failoverEndpoints = failovers.lineSequence().map(String::trim).filter(String::isNotBlank).map { PoolValidator.parseAddress(it) }.toList()
                    val resolvedName = profileName.trim().ifBlank { parsedHost.host.substringBefore('.') }
                    val resolvedWorker = worker.trim()
                    val profile = original.copy(
                        name = resolvedName,
                        primary = PoolEndpoint(parsedHost.host, parsedPort, endpointTls),
                        failovers = failoverEndpoints,
                        username = wallet.trim(),
                        worker = resolvedWorker,
                        rigId = resolvedWorker,
                        useWorkerAsPassword = true,
                        keepAlive = keepAlive,
                        connectTimeoutSeconds = timeout.toIntOrNull() ?: 15,
                        retryLimit = retries.toIntOrNull() ?: 12,
                        enabled = enabled,
                        defaultProfile = defaultProfile,
                        allowInsecureTls = false,
                    )
                    val validation = PoolValidator.validate(profile)
                    require(validation.valid) { validation.errors.joinToString("\n") }
                    profile
                }.onSuccess { profile ->
                    error = ""
                    onSave(profile, profile.worker)
                }.onFailure { failure -> error = failure.message ?: "Invalid pool profile" }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TuningHubScreen(
    settings: AppSettings,
    snapshot: MiningSnapshot,
    onOpenProfiles: () -> Unit,
    onOpenCustom: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Tuning", "Choose a validated power profile or configure the engine manually.") }
        if (snapshot.state != MiningState.STOPPED && snapshot.state != MiningState.ERROR) {
            item { InlineWarningBanner("Changes apply on restart", "The current session keeps its active worker layout and RandomX mode until mining is stopped and started again.") }
        }
        item {
            NavigationFeatureCard(
                icon = Icons.Outlined.Speed,
                title = "Power profiles",
                body = "Eco, Balanced, Performance, Maximum, and device-specific auto-tuned profiles.",
                status = settings.tuning.powerProfile.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase),
                tone = StatusTone.PERFORMANCE,
                onClick = onOpenProfiles,
            )
        }
        item {
            NavigationFeatureCard(
                icon = Icons.Outlined.Tune,
                title = "Custom tuning",
                body = "Threads, CPU placement, thermal protection, battery rules, network policy, RandomX memory, and JIT.",
                status = if (settings.tuning.powerProfile == PowerProfile.CUSTOM) "Active" else "Available",
                tone = if (settings.tuning.powerProfile == PowerProfile.CUSTOM) StatusTone.HEALTHY else StatusTone.ACTION,
                onClick = onOpenCustom,
            )
        }
    }
}

@Composable
private fun NavigationFeatureCard(
    icon: ImageVector,
    title: String,
    body: String,
    status: String,
    tone: StatusTone,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusChip(status, tone)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open $title")
        }
    }
}

@Composable
fun PowerProfilesScreen(
    settings: AppSettings,
    profiles: List<PoolProfile>,
    autoTuneState: AutoTuneUiState,
    autoTuneJson: String,
    viewModel: MainViewModel,
) {
    val selectedPool = profiles.firstOrNull { it.defaultProfile && it.enabled } ?: profiles.firstOrNull { it.enabled }
    var showConfirmation by remember { mutableStateOf(false) }
    val profileSpecs = remember {
        listOf(
            ProfileSpec(PowerProfile.ECO, "Eco", "Lowest heat and battery use for background or charging operation.", "Low", "Low", "Low", "2 workers", Icons.Outlined.BatterySaver),
            ProfileSpec(PowerProfile.BALANCED, "Balanced", "Moderate performance and thermal load for everyday use.", "Medium", "Medium", "Medium", "Automatic", Icons.Outlined.Speed),
            ProfileSpec(PowerProfile.PERFORMANCE, "Performance", "Higher thread utilization with increased heat and battery use.", "High", "High", "High", "Automatic", Icons.Outlined.Bolt),
            ProfileSpec(PowerProfile.MAXIMUM, "Maximum", "Peak configured utilization for short, controlled sessions.", "Maximum", "Maximum", "Maximum", "Automatic", Icons.Outlined.Memory),
            ProfileSpec(PowerProfile.AUTO_TUNED, "Auto tuned", "Fastest configuration measured on this device against a live pool job.", "Measured", "High", "High", "Device specific", Icons.Outlined.AutoFixHigh),
            ProfileSpec(PowerProfile.CUSTOM, "Custom", "Uses the exact values saved in Custom tuning.", "Custom", "Custom", "Custom", if (settings.tuning.threadCount == 0) "Automatic" else "${settings.tuning.threadCount} workers", Icons.Outlined.Tune),
        )
    }
    val history = remember(autoTuneJson) { runCatching { JSONArray(autoTuneJson) }.getOrDefault(JSONArray()) }
    val latestResult = history.optJSONObject(history.length() - 1)
    val clipboard = LocalClipboardManager.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Power profiles", "Select a complete performance policy before mining starts.") }
        items(profileSpecs, key = { it.profile.name }) { spec ->
            ProfileSelectionCard(
                name = spec.name,
                description = spec.description,
                selected = settings.tuning.powerProfile == spec.profile,
                performance = spec.performance,
                heat = spec.heat,
                battery = spec.battery,
                threads = spec.threads,
                icon = spec.icon,
                onSelect = { viewModel.setPowerProfile(spec.profile) },
            )
        }
        item {
            SectionHeader("Live maximum-hashrate auto-tune", "Five-minute measurement after pool login and dataset preparation")
        }
        item {
            when {
                autoTuneState.running -> {
                    ProgressPanel(
                        title = "Testing this device",
                        message = autoTuneState.message,
                        progress = if (autoTuneState.phase == AutoTunePhase.RUNNING) autoTuneState.elapsedSeconds.toFloat() / autoTuneState.targetSeconds.coerceAtLeast(1) else null,
                        elapsed = "${formatDuration(autoTuneState.elapsedSeconds.toLong())} elapsed",
                        onCancel = viewModel::cancelAutoTune,
                    )
                }
                autoTuneState.phase == AutoTunePhase.COMPLETE -> {
                    SuccessBanner("Auto-tune complete", autoTuneState.message)
                }
                autoTuneState.phase == AutoTunePhase.FAILED -> {
                    InlineBanner("Auto-tune did not complete", autoTuneState.error.ifBlank { autoTuneState.message }, StatusTone.CRITICAL)
                }
                else -> {
                    Card(
                        modifier = Modifier.fillMaxWidth().monitoringPanel(CryptoSteerThemeTokens.colors.performance),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, CryptoSteerThemeTokens.colors.performance.copy(alpha = 0.38f)),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Find the fastest stable configuration", style = MaterialTheme.typography.titleLarge)
                            Text("The test connects to ${selectedPool?.name ?: "the selected pool"}, mines real work, compares thread layouts, memory mode, JIT, and CPU placement, then saves the winner.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip("5 minute measurement", StatusTone.PERFORMANCE, icon = Icons.Outlined.Timer)
                                StatusChip("Thermal protection active", StatusTone.WARNING, icon = Icons.Outlined.Thermostat)
                                StatusChip("Real pool work", StatusTone.LIVE, icon = Icons.Outlined.CloudDone)
                            }
                            PrimaryActionButton(
                                label = "Start auto-tune",
                                icon = Icons.Outlined.AutoFixHigh,
                                enabled = selectedPool != null,
                                onClick = { showConfirmation = true },
                            )
                            if (selectedPool == null) Text("Add and enable a pool profile first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (latestResult != null) {
            item {
                val best = latestResult.optJSONObject("best") ?: JSONObject()
                BenchmarkResultCard(
                    title = "Latest auto-tune result",
                    result = "%.2f H/s".format(Locale.US, best.optDouble("hashrate")),
                    best = true,
                    details = listOf(
                        "Threads" to best.optInt("threads").toString(),
                        "Memory mode" to best.optString("mode", "Unknown"),
                        "JIT" to if (best.optBoolean("jit")) "Enabled" else "Disabled",
                        "CPU layout" to (best.optJSONArray("preferredCpus")?.toString() ?: "Automatic"),
                        "Accepted shares" to latestResult.optLong("acceptedShares").toString(),
                        "Thermal peak" to thermalLabel(latestResult.optInt("thermalPeak")),
                    ),
                    onCopy = { clipboard.setText(AnnotatedString(latestResult.toString(2))) },
                    onDelete = { viewModel.deleteAutoTune(latestResult.optLong("createdAtMillis")) },
                )
            }
        }
    }

    if (showConfirmation && selectedPool != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Run maximum-hashrate auto-tune?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The phone may become hot and battery use will be high.")
                    Text("Android thermal protection remains active and can stop the test.")
                    Text("Dataset preparation is excluded from the five-minute measurement, so total time may be longer.")
                    Text("Pool: ${selectedPool.name} • ${selectedPool.primary.host}:${selectedPool.primary.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    viewModel.runAutoTune(selectedPool.id)
                }) { Text("Start test") }
            },
            dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("Cancel") } },
        )
    }
}

private data class ProfileSpec(
    val profile: PowerProfile,
    val name: String,
    val description: String,
    val performance: String,
    val heat: String,
    val battery: String,
    val threads: String,
    val icon: ImageVector,
)

@Composable
fun CustomTuningScreen(settings: AppSettings, snapshot: MiningSnapshot, viewModel: MainViewModel) {
    val tuning = settings.tuning
    val processors = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1).coerceAtMost(64) }
    var advancedCpuText by remember(tuning.preferredCpus) { mutableStateOf(tuning.preferredCpus.joinToString(",")) }
    var cpuError by remember { mutableStateOf("") }
    var appliedMessage by remember { mutableStateOf("") }
    val miningActive = snapshot.state !in listOf(MiningState.STOPPED, MiningState.ERROR)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Custom tuning", "Fine-grained control with Android thermal protection always enforced.") }
        if (miningActive) {
            item { InlineWarningBanner("Saved for next start", "Thread layout, memory mode, JIT, and CPU placement changes require mining to restart before they become active.") }
        } else if (appliedMessage.isNotBlank()) {
            item { SuccessBanner("Settings saved", appliedMessage) }
        }
        item {
            TuningSection("Compute", "Worker count and CPU load") {
                LabeledSlider(
                    label = "Mining threads",
                    valueLabel = if (tuning.threadCount == 0) "Automatic" else tuning.threadCount.toString(),
                    value = tuning.threadCount.toFloat(),
                    range = 0f..processors.toFloat(),
                    steps = (processors - 1).coerceAtLeast(0),
                    minimumLabel = "Auto",
                    maximumLabel = processors.toString(),
                    supportingText = "Available logical CPUs: $processors. Automatic uses topology-aware placement.",
                ) { value -> viewModel.updateTuning { it.copy(threadCount = value.toInt()) }; appliedMessage = "Thread count updated." }
                LabeledSlider(
                    label = "CPU utilization target",
                    valueLabel = "${tuning.cpuUtilizationPercent}%",
                    value = tuning.cpuUtilizationPercent.toFloat(),
                    range = 10f..100f,
                    steps = 17,
                    minimumLabel = "10%",
                    maximumLabel = "100%",
                    supportingText = "Limits the configured workload target; Android can still throttle the process.",
                ) { value -> viewModel.updateTuning { it.copy(cpuUtilizationPercent = value.toInt()) }; appliedMessage = "CPU utilization updated." }
                LabeledSlider(
                    label = "Duty cycle",
                    valueLabel = "${tuning.dutyCyclePercent}%",
                    value = tuning.dutyCyclePercent.toFloat(),
                    range = 10f..100f,
                    steps = 17,
                    minimumLabel = "10%",
                    maximumLabel = "100%",
                    supportingText = "Lower values insert controlled idle time between work batches.",
                ) { value -> viewModel.updateTuning { it.copy(dutyCyclePercent = value.toInt()) }; appliedMessage = "Duty cycle updated." }
            }
        }
        item {
            TuningSection("Preferred cores", "Visual logical CPU selection") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(processors) { cpu ->
                        val selected = cpu in tuning.preferredCpus
                        Surface(
                            modifier = Modifier.size(48.dp).clickable {
                                val updated = if (selected) tuning.preferredCpus - cpu else (tuning.preferredCpus + cpu).distinct().sorted()
                                viewModel.updateTuning { it.copy(preferredCpus = updated) }
                                advancedCpuText = updated.joinToString(",")
                            },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) { Box(contentAlignment = Alignment.Center) { Text(cpu.toString(), style = MaterialTheme.typography.labelLarge) } }
                    }
                }
                Text("Leave every core unselected for automatic placement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExpandableTechnicalDetails("Expert CPU list", summary = "Comma-separated logical CPU numbers") {
                    OutlinedTextField(advancedCpuText, { advancedCpuText = it.filter { char -> char.isDigit() || char == ',' || char.isWhitespace() }.take(256) }, label = { Text("Example: 4,5,6,7") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = {
                        runCatching {
                            advancedCpuText.split(',').map(String::trim).filter(String::isNotEmpty).map { it.toInt() }.onEach { require(it in 0 until processors) { "CPU $it is outside 0–${processors - 1}." } }.distinct().sorted()
                        }.onSuccess { cpus ->
                            cpuError = ""
                            viewModel.updateTuning { it.copy(preferredCpus = cpus) }
                            appliedMessage = "Preferred cores updated."
                        }.onFailure { cpuError = it.message ?: "Invalid CPU list" }
                    }) { Text("Apply core list") }
                    if (cpuError.isNotBlank()) Text(cpuError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            TuningSection("Thermal protection", "Android thermal categories 0–6") {
                LabeledSlider(
                    "Stop thermal level",
                    thermalLabel(tuning.stopThermalStatus),
                    tuning.stopThermalStatus.toFloat(),
                    3f..6f,
                    steps = 2,
                    minimumLabel = "Severe",
                    maximumLabel = "Shutdown",
                    supportingText = "Mining pauses when Android reaches this category.",
                ) { value -> viewModel.updateTuning { it.copy(stopThermalStatus = value.toInt(), resumeThermalStatus = it.resumeThermalStatus.coerceAtMost(value.toInt() - 1)) } }
                LabeledSlider(
                    "Resume thermal level",
                    thermalLabel(tuning.resumeThermalStatus),
                    tuning.resumeThermalStatus.toFloat(),
                    0f..(tuning.stopThermalStatus - 1).coerceAtLeast(0).toFloat(),
                    steps = (tuning.stopThermalStatus - 2).coerceAtLeast(0),
                    minimumLabel = "Normal",
                    maximumLabel = thermalLabel((tuning.stopThermalStatus - 1).coerceAtLeast(0)),
                    supportingText = "Resume remains below the stop level to prevent rapid pause/resume cycling.",
                ) { value -> viewModel.updateTuning { it.copy(resumeThermalStatus = value.toInt()) } }
                if (snapshot.thermalStatus >= tuning.stopThermalStatus) InlineWarningBanner("Thermal pause active", "The current Android thermal level is ${thermalLabel(snapshot.thermalStatus)}.")
            }
        }
        item {
            TuningSection("Battery and power rules", "Policies checked before and during mining") {
                LabeledSlider(
                    "Pause below battery",
                    "${tuning.pauseBelowBatteryPercent}%",
                    tuning.pauseBelowBatteryPercent.toFloat(),
                    0f..80f,
                    steps = 15,
                    minimumLabel = "Off",
                    maximumLabel = "80%",
                ) { value -> viewModel.updateTuning { it.copy(pauseBelowBatteryPercent = value.toInt()) } }
                SettingSwitchRow("Mine only while charging", tuning.mineOnlyWhileCharging, { value -> viewModel.updateTuning { it.copy(mineOnlyWhileCharging = value) } }, supportingText = "Prevents battery-powered mining sessions.")
                SettingSwitchRow("Pause while screen is on", tuning.pauseWhenScreenOn, { value -> viewModel.updateTuning { it.copy(pauseWhenScreenOn = value) } }, supportingText = "Reduces competition with foreground interaction.")
            }
        }
        item {
            TuningSection("Network", "Connection eligibility") {
                SettingSwitchRow("Wi-Fi only", tuning.wifiOnly, { value -> viewModel.updateTuning { it.copy(wifiOnly = value) } }, supportingText = "Pauses when Wi-Fi is unavailable.")
                Text("Pool reconnection uses each profile’s configured retry limit and failover list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            TuningSection("RandomX engine", "Memory mode and JIT") {
                Text("Memory mode", style = MaterialTheme.typography.titleSmall)
                SegmentedSelector(RandomXMode.entries, tuning.randomXMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { mode -> viewModel.updateTuning { it.copy(randomXMode = mode) } }
                Text("FULL requests the complete RandomX dataset and reports allocation failure instead of silently falling back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("JIT mode", style = MaterialTheme.typography.titleSmall)
                SegmentedSelector(JitMode.entries, tuning.jitMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { mode -> viewModel.updateTuning { it.copy(jitMode = mode) } }
                if (miningActive) {
                    DefinitionRow("Effective memory now", snapshot.randomXMode.ifBlank { "Not initialized" })
                    DefinitionRow("JIT now", if (snapshot.jitEnabled) "Enabled" else "Disabled")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryActionButton("Restore recommended", { viewModel.setPowerProfile(PowerProfile.BALANCED) }, Modifier.weight(1f), Icons.Outlined.Refresh)
                DestructiveActionButton("Reset all", viewModel::resetTuning, Modifier.weight(1f), Icons.Outlined.Delete)
            }
        }
    }
}

@Composable
private fun TuningSection(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeader(title, subtitle)
            content()
        }
    }
}

@Composable
fun MoreScreen(
    onBenchmark: () -> Unit,
    onStatistics: () -> Unit,
    onDiagnostics: () -> Unit,
    onLogs: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onPrivacy: () -> Unit,
    onTroubleshooting: () -> Unit,
) {
    val items = listOf(
        MoreItem("Benchmark", "Measure local RandomX performance without connecting to a pool.", Icons.Outlined.Timer, onBenchmark),
        MoreItem("Statistics", "Session history, acceptance rate, latency, thermal time, and donation time.", Icons.Outlined.BarChart, onStatistics),
        MoreItem("Diagnostics", "Structured native, parser, TLS, and page-size correctness checks.", Icons.Outlined.BugReport, onDiagnostics),
        MoreItem("Logs", "Searchable bounded event history with severity and category filters.", Icons.Outlined.Terminal, onLogs),
        MoreItem("Application settings", "Runtime, foreground-service, privacy, and system-theme behavior.", Icons.Outlined.Settings, onSettings),
        MoreItem("About", "Version, build, donation disclosure, and native compatibility.", Icons.Outlined.Info, onAbout),
        MoreItem("Privacy and data", "What remains on device and which network endpoints are contacted.", Icons.Outlined.PrivacyTip, onPrivacy),
        MoreItem("Troubleshooting", "Actionable guidance for pool, thermal, memory, and service issues.", Icons.Outlined.HelpOutline, onTroubleshooting),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("More", "Tools, reports, application information, and support") }
        items(items, key = { it.title }) { item -> NavigationFeatureCard(item.icon, item.title, item.description, "Open", StatusTone.NEUTRAL, item.onClick) }
    }
}

private data class MoreItem(val title: String, val description: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun BenchmarkScreen(
    benchmarkJson: String,
    benchmarkState: BenchmarkUiState,
    snapshot: MiningSnapshot,
    viewModel: MainViewModel,
) {
    var threads by rememberSaveable { mutableIntStateOf(Runtime.getRuntime().availableProcessors().coerceIn(1, 64)) }
    var seconds by rememberSaveable { mutableIntStateOf(30) }
    var mode by rememberSaveable { mutableStateOf(RandomXMode.LIGHT) }
    var jitMode by rememberSaveable { mutableStateOf(JitMode.AUTO) }
    val history = remember(benchmarkJson) { runCatching { JSONArray(benchmarkJson) }.getOrDefault(JSONArray()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val results = remember(history.toString()) { buildList { for (index in 0 until history.length()) history.optJSONObject(index)?.let(::add) } }.sortedByDescending { it.optLong("createdAtMillis") }
    val bestHashrate = results.maxOfOrNull { it.optDouble("hashrate") } ?: 0.0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Local benchmark", "Measure the device without pool or donation traffic") }
        item { InlineBanner("Private and local", "Benchmark work never connects to a pool and is not counted as donation mining.", StatusTone.LIVE) }
        if (snapshot.state !in listOf(MiningState.STOPPED, MiningState.ERROR)) {
            item { InlineWarningBanner("Mining is active", "Stop the current mining session before starting a local benchmark.") }
        }
        item {
            TuningSection("Configuration", "Dataset preparation is shown separately from timed measurement") {
                LabeledSlider("Threads", threads.toString(), threads.toFloat(), 1f..64f, steps = 62, minimumLabel = "1", maximumLabel = "64") { threads = it.toInt() }
                LabeledSlider("Duration", "$seconds seconds", seconds.toFloat(), 5f..300f, steps = 58, minimumLabel = "5 sec", maximumLabel = "5 min") { seconds = (it.toInt() / 5 * 5).coerceIn(5, 300) }
                Text("RandomX memory", style = MaterialTheme.typography.titleSmall)
                SegmentedSelector(listOf(RandomXMode.LIGHT, RandomXMode.FULL), mode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { mode = it }
                Text("JIT", style = MaterialTheme.typography.titleSmall)
                SegmentedSelector(JitMode.entries, jitMode, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { jitMode = it }
                PrimaryActionButton(
                    label = "Run benchmark",
                    icon = Icons.Outlined.PlayArrow,
                    enabled = !benchmarkState.running && snapshot.state in listOf(MiningState.STOPPED, MiningState.ERROR),
                    onClick = { viewModel.startBenchmark(threads, seconds, mode, jitMode, emptyList()) },
                )
            }
        }
        if (benchmarkState.running) {
            item {
                ProgressPanel(
                    title = when (benchmarkState.phase) {
                        BenchmarkPhase.PREPARING -> "Preparing RandomX memory"
                        BenchmarkPhase.CANCELLING -> "Cancelling benchmark"
                        else -> "Benchmark in progress"
                    },
                    message = benchmarkState.message,
                    progress = if (benchmarkState.phase == BenchmarkPhase.RUNNING) benchmarkState.elapsedSeconds.toFloat() / benchmarkState.targetSeconds.coerceAtLeast(1) else null,
                    elapsed = "${formatDuration(benchmarkState.elapsedSeconds.toLong())} of ${formatDuration(benchmarkState.targetSeconds.toLong())}",
                    onCancel = viewModel::cancelBenchmark,
                )
            }
        }
        if (benchmarkState.phase == BenchmarkPhase.COMPLETE) item { SuccessBanner("Benchmark complete", benchmarkState.message) }
        if (benchmarkState.phase == BenchmarkPhase.FAILED) item { InlineBanner("Benchmark failed", benchmarkState.error.ifBlank { benchmarkState.message }, StatusTone.CRITICAL) }
        item { SectionHeader("Saved results", "Compare compatible runs on this device") }
        if (results.isEmpty()) {
            item { EmptyState("No benchmark results", "Run a benchmark to create the first local result.", icon = Icons.Outlined.Timer) }
        } else {
            items(results, key = { it.optLong("createdAtMillis") }) { result ->
                val created = result.optLong("createdAtMillis")
                val resultText = result.toString(2)
                BenchmarkResultCard(
                    title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(created)),
                    result = "%.2f H/s".format(Locale.US, result.optDouble("hashrate")),
                    best = result.optDouble("hashrate") >= bestHashrate && bestHashrate > 0.0,
                    details = listOf(
                        "Mode" to result.optString("mode", "Unknown"),
                        "Threads" to result.optInt("threads").toString(),
                        "Duration" to "${result.optInt("durationSeconds")} seconds",
                        "Peak" to "%.2f H/s".format(Locale.US, result.optDouble("peakHashrate", result.optDouble("hashrate"))),
                        "Thermal peak" to thermalLabel(result.optInt("thermalPeak")),
                        "Battery change" to "${result.optInt("batteryDeltaPercent")}%",
                        "Page size" to "${result.optLong("pageSize")} bytes",
                    ),
                    onCopy = { clipboard.setText(AnnotatedString(resultText)) },
                    onDelete = { viewModel.deleteBenchmark(created) },
                )
            }
            item {
                SecondaryActionButton("Share JSON report", {
                    shareText(context, "CryptoSteer benchmark report", results.joinToString(prefix = "[", postfix = "]") { it.toString() })
                }, icon = Icons.Outlined.Share)
            }
        }
    }
}

@Composable
fun StatisticsScreen(history: List<HashratePoint>, snapshot: MiningSnapshot) {
    var range by rememberSaveable { mutableStateOf(ChartRange.FIFTEEN_MINUTES) }
    val acceptedTotal = snapshot.accepted + snapshot.rejected + snapshot.stale
    val acceptanceRate = if (acceptedTotal == 0L) 0.0 else snapshot.accepted * 100.0 / acceptedTotal.toDouble()
    val minimum = history.minOfOrNull { it.hashrate } ?: 0.0
    val maximum = history.maxOfOrNull { it.hashrate } ?: 0.0
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Statistics", "Bounded local session history with no analytics SDK") }
        item { LiveHashrateChart(history, selectedRange = range, onRangeSelected = { range = it }) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    DashboardMetricSpec("Current", "%.2f H/s".format(Locale.US, snapshot.hashrate), Icons.Outlined.Speed, StatusTone.LIVE),
                    DashboardMetricSpec("Average", "%.2f H/s".format(Locale.US, snapshot.averageHashrate), Icons.Outlined.BarChart, StatusTone.PERFORMANCE),
                    DashboardMetricSpec("Minimum", "%.2f H/s".format(Locale.US, minimum), Icons.AutoMirrored.Outlined.ArrowForward, StatusTone.NEUTRAL),
                    DashboardMetricSpec("Maximum", "%.2f H/s".format(Locale.US, maximum), Icons.Outlined.Bolt, StatusTone.PERFORMANCE),
                    DashboardMetricSpec("Completed hashes", snapshot.hashes.toString(), Icons.Outlined.Memory, StatusTone.NEUTRAL),
                    DashboardMetricSpec("Acceptance rate", "%.2f%%".format(Locale.US, acceptanceRate), Icons.Outlined.CheckCircle, if (snapshot.rejected > 0) StatusTone.WARNING else StatusTone.HEALTHY),
                ).chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { metric -> MetricCard(metric.label, metric.value, Modifier.weight(1f), icon = metric.icon, tone = metric.tone) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("Session totals")
                    DefinitionRow("Accepted", snapshot.accepted.toString(), tone = StatusTone.HEALTHY)
                    DefinitionRow("Rejected", snapshot.rejected.toString(), tone = if (snapshot.rejected > 0) StatusTone.WARNING else StatusTone.NEUTRAL)
                    DefinitionRow("Stale", snapshot.stale.toString())
                    DefinitionRow("Average pool latency", if (snapshot.poolLatencyMillis > 0) "${snapshot.poolLatencyMillis} ms" else "Not measured")
                    DefinitionRow("Mining time", formatDuration(snapshot.uptimeSeconds))
                    DefinitionRow("Donation time", formatNanos(snapshot.donationMiningNanos))
                    DefinitionRow("Donation percentage", "%.6f%%".format(Locale.US, snapshot.donationPercent))
                }
            }
        }
        item { Text("All samples are generated and retained locally. History is bounded and chart points are decimated before drawing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private data class DashboardMetricSpec(val label: String, val value: String, val icon: ImageVector, val tone: StatusTone)

@Composable
fun DiagnosticsScreen(snapshot: MiningSnapshot, diagnosticsState: DiagnosticsUiState, viewModel: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val nativeVersion = remember { runCatching { JSONObject(com.thecryptosteer.xmrminer.nativebridge.NativeMiner.nativeVersion()).optString("nativeVersion") }.getOrDefault("Unavailable") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Diagnostics", "Structured runtime and correctness verification") }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("Device and runtime")
                    DefinitionRow("Application", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    DefinitionRow("Native engine", nativeVersion.ifBlank { "Unavailable" })
                    DefinitionRow("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
                    DefinitionRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    DefinitionRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
                    DefinitionRow("Runtime page size", runtimePageSizeLabel(snapshot.pageSize))
                    DefinitionRow("RandomX mode", snapshot.randomXMode.ifBlank { "Not initialized" })
                    DefinitionRow("JIT", if (snapshot.jitEnabled) "Available and active" else "Disabled or unavailable")
                    DefinitionRow("Hardware AES", if (snapshot.hardwareAes) "Available" else "Unavailable")
                    DefinitionRow("Thermal state", thermalLabel(snapshot.thermalStatus))
                }
            }
        }
        item {
            PrimaryActionButton(
                label = if (diagnosticsState.running) "Diagnostics running" else "Run diagnostics",
                icon = Icons.Outlined.BugReport,
                loading = diagnosticsState.running,
                onClick = viewModel::runDiagnostics,
            )
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeader("Correctness checks")
                    diagnosticsState.checks.forEachIndexed { index, check ->
                        DiagnosticResultRow(check.title, check.status, detail = check.detail)
                        if (index != diagnosticsState.checks.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        diagnosticsState.overallPassed?.let { passed ->
            item {
                if (passed) SuccessBanner("Diagnostics passed", "All completed checks passed in ${diagnosticsState.durationMillis} ms.")
                else InlineBanner("Diagnostics partially failed", "One or more checks need attention. Copy or share the report for troubleshooting.", StatusTone.CRITICAL)
            }
        }
        if (diagnosticsState.reportJson.isNotBlank()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryActionButton("Copy report", { clipboard.setText(AnnotatedString(diagnosticsState.reportJson)) }, Modifier.weight(1f), Icons.Outlined.ContentCopy)
                    SecondaryActionButton("Share report", { shareText(context, "CryptoSteer diagnostics", diagnosticsState.reportJson) }, Modifier.weight(1f), Icons.Outlined.Share)
                }
            }
        }
        item { InlineBanner("Page-size note", "A 4096-byte runtime page size is supported. It is different from the APK requirement that native libraries remain compatible with 16 KiB Android pages.", StatusTone.LIVE) }
    }
}

private enum class LogFilter(val label: String) { ALL("All"), INFO("Info"), WARNINGS("Warnings"), ERRORS("Errors"), POOL("Pool"), MINING("Mining"), THERMAL("Thermal"), DONATION("Donation"), NATIVE("Native") }

private data class GroupedLog(val entry: LogEntry, val count: Int)

@Composable
fun LogsScreen(logs: List<LogEntry>, clear: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var paused by rememberSaveable { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val latestLogs by rememberUpdatedState(logs)
    val source = if (paused) frozen else logs
    val filtered = remember(source, filter, query) {
        source.asReversed().filter { entry ->
            val message = entry.message.lowercase()
            val level = entry.level.uppercase()
            val matchesQuery = query.isBlank() || message.contains(query.lowercase()) || level.contains(query.uppercase())
            val matchesFilter = when (filter) {
                LogFilter.ALL -> true
                LogFilter.INFO -> level == "INFO" || level == "SUCCESS"
                LogFilter.WARNINGS -> level == "WARN" || level == "WARNING"
                LogFilter.ERRORS -> level == "ERROR" || level == "CRITICAL"
                LogFilter.POOL -> message.contains("pool") || message.contains("stratum") || message.contains("connect")
                LogFilter.MINING -> message.contains("mining") || message.contains("hash") || message.contains("job")
                LogFilter.THERMAL -> message.contains("thermal") || message.contains("temperature") || message.contains("cool")
                LogFilter.DONATION -> message.contains("donation")
                LogFilter.NATIVE -> message.contains("native") || message.contains("randomx") || message.contains("jit")
            }
            matchesQuery && matchesFilter
        }
    }
    val grouped = remember(filtered) { groupLogs(filtered) }
    val visibleText = remember(grouped) {
        grouped.joinToString("\n") { item ->
            "${DateFormat.getDateTimeInstance().format(Date(item.entry.timeMillis))} ${item.entry.level} ${item.entry.message}${if (item.count > 1) " ×${item.count}" else ""}"
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenHeader(
                title = "Logs",
                subtitle = "Local bounded event history; credentials and full wallet strings are redacted.",
                action = {
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.Delete, "Clear logs", tint = MaterialTheme.colorScheme.error) }
                },
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(100) },
                label = { Text("Search logs") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item { SegmentedSelector(LogFilter.entries, filter, { it.label }) { filter = it } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton(
                    label = if (paused) "Resume live list" else "Pause live list",
                    icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!paused) frozen = latestLogs
                        paused = !paused
                    },
                )
                SecondaryActionButton("Copy visible", { clipboard.setText(AnnotatedString(visibleText)) }, Modifier.weight(1f), Icons.Outlined.ContentCopy, enabled = grouped.isNotEmpty())
                IconButton(onClick = { shareText(context, "CryptoSteer logs", visibleText) }, enabled = grouped.isNotEmpty()) { Icon(Icons.Outlined.Share, "Share visible logs") }
            }
        }
        if (logs.lastOrNull { it.message.contains("Donation pool connection failed", true) } != null) {
            item { InlineWarningBanner("Donation connection warning", "User mining continues. Donation time is not increased to compensate, and repeated identical warnings are grouped below.") }
        }
        if (grouped.isEmpty()) {
            item { EmptyState("No matching log entries", if (logs.isEmpty()) "Events will appear after the app or miner performs work." else "Change the search or filter to see other events.", icon = Icons.AutoMirrored.Outlined.List) }
        } else {
            items(grouped, key = { "${it.entry.timeMillis}:${it.entry.message}" }) { item ->
                LogEntryRow(item.entry, item.count)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear local logs?") },
            text = { Text("This removes the bounded on-device event history. It does not change mining settings or accounting.") },
            confirmButton = {
                TextButton(onClick = { clear(); confirmClear = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SettingsScreen(snapshot: MiningSnapshot) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Application settings", "Release and runtime behavior") }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("Appearance")
                    DefinitionRow("Theme", "Follows Android light or dark mode")
                    DefinitionRow("Live UI refresh", "Stable readable sampling")
                    DefinitionRow("Navigation", "Bottom bar, rail, or persistent panel")
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("Mining behavior")
                    DefinitionRow("Foreground service", "Always visible while mining")
                    DefinitionRow("Automatic mining after reboot", "Disabled")
                    DefinitionRow("TLS verification", "Enabled for user profiles using TLS")
                    DefinitionRow("Current state", miningStateLabel(snapshot.state))
                }
            }
        }
        item { InlineBanner("Credential protection", "Pool credentials are encrypted with an Android Keystore-backed AES-GCM key and redacted from ordinary logs.", StatusTone.LIVE) }
        item { InlineBanner("Android controls remain authoritative", "The app does not bypass battery restrictions, network policy, process lifecycle, or thermal protection.", StatusTone.NEUTRAL) }
    }
}

@Composable
fun AboutScreen(snapshot: MiningSnapshot, donationTier: DonationTierUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().monitoringPanel(MaterialTheme.colorScheme.primary),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("CryptoSteer XMR Miner", style = MaterialTheme.typography.headlineLarge)
                    Text("THE CRYPTO STEER LLC • Advanced Android XMR Miner", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusChip("Version ${BuildConfig.VERSION_NAME}", StatusTone.ACTION)
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader("Build")
                    DefinitionRow("Version code", BuildConfig.VERSION_CODE.toString())
                    DefinitionRow("Package", BuildConfig.APPLICATION_ID)
                    DefinitionRow("Architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
                    DefinitionRow("Runtime page size", runtimePageSizeLabel(snapshot.pageSize))
                    DefinitionRow("Distribution", BuildConfig.DISTRIBUTION_LABEL)
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionHeader(donationTier.percentLabel, "Current developer contribution")
                    DefinitionRow("Current target", donationTier.percentLabel)
                    DefinitionRow("Actual cumulative ratio", "%.6f%%".format(Locale.US, snapshot.donationPercent))
                    DefinitionRow("Tier progress", if (donationTier.basisPoints <= BuildConfig.REDUCED_DONATION_BASIS_POINTS) "1% lifetime tier unlocked" else "${formatNanos(donationTier.remainingNanos)} of active mining remains")
                    DefinitionRow("Progress scope", if (donationTier.accountScoped) "Combined across this signed-in account's approved rigs" else "Sign in to combine progress across rigs")
                    Text(BuildConfig.DONATION_WALLET, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text("© 2026 THE CRYPTO STEER LLC. Licensed under Apache-2.0. No advertisements or third-party analytics SDKs are included. Optional account and remote-control data is limited to approved rigs on the signed-in account.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun PrivacyScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Privacy and data", "Local-first operation with direct pool connections") }
        item { PrivacyCard("Stored on this device", "Pool profiles, encrypted credentials, tuning preferences, bounded logs, bounded hashrate history, benchmark results, auto-tune results, and donation accounting.", Icons.Outlined.Save) }
        item { PrivacyCard("Network connections", if (BuildConfig.SELF_UPDATE_ENABLED) "The direct release connects to user-selected pools, the disclosed donation pool during contribution periods, and THE CRYPTO STEER LLC control service for signed releases. Optional account features begin only after sign-in." else "The F-Droid build connects to user-selected pools and the disclosed donation pool during contribution periods. It contains no private APK updater. Optional account features begin only after sign-in.", Icons.Outlined.Public) }
        item { PrivacyCard("Operational telemetry", if (BuildConfig.INSTALLATION_TELEMETRY_ENABLED) "The direct release sends a random installation identifier, app/device version, online state, mining state, and hashrate after disclosure acceptance. No advertising ID, contacts, precise location, or third-party analytics SDK is used." else "The F-Droid build does not send anonymous installation, online-state, mining-state, or hashrate telemetry. Account and remote-control data is sent only after you sign in.", Icons.Outlined.PrivacyTip) }
        item { PrivacyCard("Credentials", "Pool credentials are encrypted with Android Keystore-backed AES-GCM and redacted before logs are displayed.", Icons.Outlined.CheckCircle) }
    }
}

@Composable
private fun PrivacyCard(title: String, body: String, icon: ImageVector) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TroubleshootingScreen() {
    val entries = listOf(
        "Pool connection fails" to "Verify the host, port, wallet, worker name, and transport. TLS profiles require a certificate matching the pool hostname.",
        "Mining remains paused" to "Review charging, battery threshold, Wi-Fi-only mode, screen-on policy, and Android thermal status in Custom tuning.",
        "Full-memory mode fails" to "RandomX FULL needs substantial available memory. Close other heavy apps or use Light mode.",
        "No accepted shares yet" to "Mobile hashrate and pool difficulty can create long intervals. Confirm that a pool job is active and monitor latency and logs.",
        "Donation connection fails" to "User mining continues and the fee is not increased to compensate. The app retries the disclosed contribution connection automatically.",
        "JIT unavailable" to "The engine uses its safe interpreter path when executable-memory transitions are unavailable.",
        "Android stops the service" to "Keep the foreground notification visible and review system battery restrictions. CryptoSteer does not bypass Android process controls.",
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("Troubleshooting", "Common issues and direct next steps") }
        items(entries, key = { it.first }) { (title, body) ->
            ExpandableTechnicalDetails(title, summary = body.take(72) + if (body.length > 72) "…" else "") {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun groupLogs(entries: List<LogEntry>): List<GroupedLog> {
    if (entries.isEmpty()) return emptyList()
    val grouped = mutableListOf<GroupedLog>()
    entries.forEach { entry ->
        val existingIndex = grouped.indexOfFirst { it.entry.level == entry.level && it.entry.message == entry.message }
        if (existingIndex >= 0) {
            val existing = grouped[existingIndex]
            grouped[existingIndex] = existing.copy(count = existing.count + 1)
        } else {
            grouped += GroupedLog(entry, 1)
        }
    }
    return grouped
}

private fun shareText(context: android.content.Context, subject: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun formatNanos(value: Long): String = formatDuration(value.coerceAtLeast(0L) / 1_000_000_000L)

private fun formatTimestamp(value: Long): String = if (value <= 0L) "Not yet" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(value))

private fun stateTone(state: MiningState): StatusTone = when (state) {
    MiningState.MINING_USER, MiningState.MINING_DONATION -> StatusTone.HEALTHY
    MiningState.STARTING, MiningState.CONNECTING, MiningState.INITIALIZING -> StatusTone.LIVE
    MiningState.PAUSED, MiningState.STOPPING -> StatusTone.WARNING
    MiningState.ERROR -> StatusTone.CRITICAL
    MiningState.STOPPED -> StatusTone.NEUTRAL
}
