package com.thecryptosteer.xmrminer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.LogEntry
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.MiningState
import com.thecryptosteer.xmrminer.domain.PoolProfile
import java.text.DateFormat
import java.util.Date

@Composable
fun DashboardScreen(
    snapshot: MiningSnapshot,
    profiles: List<PoolProfile>,
    history: List<HashratePoint>,
    logs: List<LogEntry>,
    onStart: (String, (Result<Unit>) -> Unit) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenMining: () -> Unit,
) {
    val selected = profiles.firstOrNull { it.defaultProfile && it.enabled } ?: profiles.firstOrNull { it.enabled }
    val donationFailure = logs.lastOrNull { it.message.contains("Donation pool connection failed", ignoreCase = true) }
    var actionMessage by remember { mutableStateOf("") }
    var chartRange by rememberSaveable { mutableStateOf(ChartRange.LIVE) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 920.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(0.95f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiningHero(snapshot, selected, onStart, onPause, onResume, onStop, onOpenProfiles) { actionMessage = it }
                            DashboardGaugeRow(snapshot)
                            DeviceHealthCard(snapshot)
                        }
                        Column(Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DashboardMetricGrid(snapshot, columns = 2)
                            LiveHashrateChart(history, selectedRange = chartRange, onRangeSelected = { chartRange = it })
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiningHero(snapshot, selected, onStart, onPause, onResume, onStop, onOpenProfiles) { actionMessage = it }
                        DashboardGaugeRow(snapshot)
                        DashboardMetricGrid(snapshot, columns = 2)
                        LiveHashrateChart(history, selectedRange = chartRange, onRangeSelected = { chartRange = it })
                        DeviceHealthCard(snapshot)
                    }
                }
            }
            if (actionMessage.isNotBlank()) {
                item {
                    if (actionMessage.startsWith("Error:", ignoreCase = true)) {
                        InlineBanner("Action failed", actionMessage.removePrefix("Error:").trim(), StatusTone.CRITICAL)
                    } else {
                        SuccessBanner("Mining control updated", actionMessage)
                    }
                }
            }
            if (donationFailure != null) {
                item {
                    InlineWarningBanner(
                        title = "Donation pool unavailable",
                        message = "User mining continues normally. Donation time is not increased to compensate. Last attempt: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(donationFailure.timeMillis))}.",
                        actionLabel = "View mining details",
                        onAction = onOpenMining,
                    )
                }
            }
            if (snapshot.lastError.isNotBlank()) {
                item {
                    InlineBanner(
                        title = if (snapshot.state == MiningState.ERROR) "Mining needs attention" else "Recovered error",
                        message = snapshot.lastError,
                        tone = if (snapshot.state == MiningState.ERROR) StatusTone.CRITICAL else StatusTone.WARNING,
                        actionLabel = "Open details",
                        onAction = onOpenMining,
                    )
                }
            }
            item {
                ExpandableTechnicalDetails(
                    title = "Engine details",
                    summary = engineSummary(snapshot),
                ) {
                    DefinitionRow("Pool job", if (snapshot.jobReceived) "Active" else "Waiting")
                    DefinitionRow("Requested memory", snapshot.requestedRandomXMode.ifBlank { "Not initialized" })
                    DefinitionRow("Effective memory", snapshot.randomXMode.ifBlank { "Not initialized" })
                    DefinitionRow("Dataset", if (snapshot.datasetProgress >= 100) "Ready" else "${snapshot.datasetProgress}% prepared")
                    DefinitionRow("JIT", if (snapshot.jitEnabled) "Enabled" else "Disabled or unavailable")
                    DefinitionRow("Hardware AES", if (snapshot.hardwareAes) "Available" else "Unavailable")
                    DefinitionRow("Runtime page size", if (snapshot.pageSize > 0L) "${snapshot.pageSize / 1024L} KiB" else "Not reported")
                }
            }
        }
    }
}

@Composable
private fun MiningHero(
    snapshot: MiningSnapshot,
    selected: PoolProfile?,
    onStart: (String, (Result<Unit>) -> Unit) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenProfiles: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .monitoringPanel(heroAccent(snapshot.state)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonitoringPanelHeader(
                title = "Mining engine",
                subtitle = selected?.name ?: "No active pool profile",
                accent = heroAccent(snapshot.state),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MiningStatusChip(snapshot.state)
                        LiveDataChip(snapshot.updatedAtMillis)
                    }
                    Text(
                        humanMiningSummary(snapshot.state, snapshot.statusMessage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ThermalStatusIndicator(snapshot.thermalStatus)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%7.1f".format(snapshot.hashrate),
                    style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.width(8.dp))
                Text("H/s", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 7.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(snapshot.randomXMode.ifBlank { "RandomX pending" }, StatusTone.PERFORMANCE, icon = Icons.Outlined.Memory)
                StatusChip("${snapshot.workers} workers", StatusTone.LIVE, icon = Icons.Outlined.Tune)
                StatusChip(
                    "${snapshot.batteryPercent}%${if (snapshot.charging) " charging" else ""}",
                    if (snapshot.batteryPercent <= 20 && !snapshot.charging) StatusTone.WARNING else StatusTone.NEUTRAL,
                    icon = if (snapshot.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CloudQueue, null, tint = CryptoSteerThemeTokens.colors.live)
                    Column(Modifier.weight(1f)) {
                        Text(selected?.name ?: "No pool profile", style = MaterialTheme.typography.titleSmall)
                        Text(
                            snapshot.activePool.ifBlank { selected?.let { "${it.primary.host}:${it.primary.port}" } ?: "Add a pool before mining" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    ConnectionStatusChip(snapshot.poolConnected, snapshot.jobReceived)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (snapshot.state) {
                    MiningState.STOPPED, MiningState.ERROR -> PrimaryActionButton(
                        label = if (selected == null) "Add pool" else "Start mining",
                        icon = Icons.Outlined.PlayArrow,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (selected == null) onOpenProfiles()
                            else onStart(selected.id) { result -> onMessage(result.exceptionOrNull()?.let { "Error: ${it.message}" } ?: "Mining started.") }
                        },
                    )
                    MiningState.PAUSED -> PrimaryActionButton(
                        label = "Resume",
                        icon = Icons.Outlined.PlayArrow,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onResume()
                            onMessage("Mining resumed.")
                        },
                    )
                    else -> PrimaryActionButton(
                        label = "Pause",
                        icon = Icons.Outlined.Pause,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPause()
                            onMessage("Mining paused.")
                        },
                    )
                }
                if (snapshot.state != MiningState.STOPPED) {
                    DestructiveActionButton(
                        label = "Stop",
                        icon = Icons.Outlined.Stop,
                        modifier = Modifier.weight(0.72f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop()
                            onMessage("Mining stopped.")
                        },
                    )
                } else {
                    SecondaryActionButton("Pool", onOpenProfiles, Modifier.weight(0.72f), Icons.Outlined.CloudQueue)
                }
            }
        }
    }
}


@Composable
private fun DashboardGaugeRow(snapshot: MiningSnapshot) {
    val thermalFraction = thermalPressureFraction(snapshot.thermalStatus)
    val lostShares = snapshot.rejected + snapshot.stale
    val totalShares = snapshot.accepted + lostShares
    val shareLossRate = shareLossFraction(snapshot.accepted, snapshot.rejected, snapshot.stale)
    val shareLossLabel = if (totalShares > 0L) "%.1f%%".format(shareLossRate * 100f) else "—"
    val shareAccent = when {
        totalShares == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
        shareLossRate >= 0.10f -> CryptoSteerThemeTokens.colors.critical
        shareLossRate >= 0.03f -> CryptoSteerThemeTokens.colors.warning
        else -> CryptoSteerThemeTokens.colors.healthy
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SemiCircleGauge(
            title = "Thermal pressure",
            subtitle = "Android thermal state",
            fraction = thermalFraction,
            displayValue = thermalLabel(snapshot.thermalStatus),
            modifier = Modifier.weight(1f),
        )
        SemiCircleGauge(
            title = "Share loss",
            subtitle = "Rejected + stale",
            fraction = shareLossRate,
            displayValue = shareLossLabel,
            accent = shareAccent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun heroAccent(state: MiningState): Color = when (state) {
    MiningState.MINING_USER, MiningState.MINING_DONATION -> CryptoSteerThemeTokens.colors.healthy
    MiningState.CONNECTING, MiningState.INITIALIZING, MiningState.STARTING -> CryptoSteerThemeTokens.colors.live
    MiningState.PAUSED, MiningState.STOPPING -> CryptoSteerThemeTokens.colors.warning
    MiningState.ERROR -> CryptoSteerThemeTokens.colors.critical
    MiningState.STOPPED -> MaterialTheme.colorScheme.primary
}

@Composable
private fun toneAccent(tone: StatusTone): Color = when (tone) {
    StatusTone.HEALTHY -> CryptoSteerThemeTokens.colors.healthy
    StatusTone.WARNING -> CryptoSteerThemeTokens.colors.warning
    StatusTone.CRITICAL -> CryptoSteerThemeTokens.colors.critical
    StatusTone.LIVE -> CryptoSteerThemeTokens.colors.live
    StatusTone.PERFORMANCE -> CryptoSteerThemeTokens.colors.performance
    StatusTone.ACTION -> MaterialTheme.colorScheme.primary
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DashboardMetricGrid(snapshot: MiningSnapshot, columns: Int) {
    val values = listOf(
        DashboardMetric("Average", "%.1f H/s".format(snapshot.averageHashrate), Icons.Outlined.Speed, StatusTone.LIVE, "Session average"),
        DashboardMetric("Session", formatDuration(snapshot.uptimeSeconds), Icons.Outlined.Schedule, StatusTone.NEUTRAL, "Active runtime"),
        DashboardMetric("Accepted", snapshot.accepted.toString(), Icons.Outlined.CheckCircle, StatusTone.HEALTHY, "${snapshot.rejected} rejected • ${snapshot.stale} stale"),
        DashboardMetric("Latency", if (snapshot.poolLatencyMillis > 0L) "${snapshot.poolLatencyMillis} ms" else "—", Icons.Outlined.CloudQueue, if (snapshot.poolLatencyMillis > 1_000L) StatusTone.WARNING else StatusTone.NEUTRAL, "Pool round trip"),
        DashboardMetric("Battery", "${snapshot.batteryPercent}%", if (snapshot.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd, if (snapshot.batteryPercent <= 20 && !snapshot.charging) StatusTone.WARNING else StatusTone.NEUTRAL, if (snapshot.charging) "Charging" else "On battery"),
        DashboardMetric("Thermal", thermalLabel(snapshot.thermalStatus), Icons.Outlined.Thermostat, if (snapshot.thermalStatus >= 3) StatusTone.WARNING else StatusTone.HEALTHY, "Android thermal status"),
        DashboardMetric("Workers", snapshot.workers.toString(), Icons.Outlined.Memory, StatusTone.PERFORMANCE, "Active threads"),
        DashboardMetric("Donation", "%.2f%%".format(snapshot.donationPercent), Icons.Outlined.Bolt, StatusTone.NEUTRAL, "Cumulative active time"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { item ->
                    MetricCard(item.label, item.value, Modifier.weight(1f), item.supporting, item.icon, item.tone)
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class DashboardMetric(
    val label: String,
    val value: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tone: StatusTone,
    val supporting: String,
)

@Composable
private fun DeviceHealthCard(snapshot: MiningSnapshot) {
    val thermalTone = when {
        snapshot.thermalStatus >= 5 -> StatusTone.CRITICAL
        snapshot.thermalStatus >= 3 -> StatusTone.WARNING
        else -> StatusTone.HEALTHY
    }
    Card(
        modifier = Modifier.fillMaxWidth().monitoringPanel(toneAccent(thermalTone)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MonitoringPanelHeader("Device health", subtitle = "Thermal and power safeguards", accent = toneAccent(thermalTone))
            CompactMetricRow("Thermal level", thermalLabel(snapshot.thermalStatus), icon = Icons.Outlined.Thermostat, tone = thermalTone, supporting = thermalGuidance(snapshot.thermalStatus))
            CompactMetricRow("Battery", "${snapshot.batteryPercent}%", icon = if (snapshot.charging) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryStd, tone = if (snapshot.batteryPercent <= 20 && !snapshot.charging) StatusTone.WARNING else StatusTone.NEUTRAL, supporting = if (snapshot.charging) "Connected to external power" else "Mining is using battery power")
            CompactMetricRow("Active intensity", intensityLabel(snapshot), icon = Icons.Outlined.Bolt, tone = StatusTone.PERFORMANCE, supporting = "Based on active workers and RandomX memory mode")
        }
    }
}

private fun engineSummary(snapshot: MiningSnapshot): String = when {
    snapshot.state == MiningState.STOPPED -> "Engine is idle"
    snapshot.jobReceived && snapshot.datasetProgress >= 100 -> "Mining normally"
    snapshot.datasetProgress in 1..99 -> "Dataset ${snapshot.datasetProgress}% prepared"
    snapshot.poolConnected -> "Connected and waiting for work"
    else -> "Waiting for engine status"
}

private fun thermalGuidance(status: Int): String = when {
    status >= 5 -> "Emergency protection may stop mining immediately"
    status >= 3 -> "Elevated heat; throttling or a thermal pause may occur"
    status >= 1 -> "Device is warm but operating within Android limits"
    else -> "No thermal pressure reported"
}

private fun intensityLabel(snapshot: MiningSnapshot): String = when {
    snapshot.workers >= 8 && snapshot.randomXMode.equals("FULL", true) -> "Maximum"
    snapshot.workers >= 5 -> "High"
    snapshot.workers >= 3 -> "Balanced"
    snapshot.workers > 0 -> "Low"
    else -> "Idle"
}
