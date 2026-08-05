package com.thecryptosteer.xmrminer.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.thecryptosteer.xmrminer.device.CpuCoreInfo
import com.thecryptosteer.xmrminer.device.DeviceInfoSnapshot
import com.thecryptosteer.xmrminer.device.DeviceInfoUiState
import com.thecryptosteer.xmrminer.device.deviceInfoReport
import com.thecryptosteer.xmrminer.device.formatBinaryBytes
import com.thecryptosteer.xmrminer.device.formatCpuIds
import com.thecryptosteer.xmrminer.device.formatFrequency
import com.thecryptosteer.xmrminer.domain.AppSettings
import com.thecryptosteer.xmrminer.domain.MiningSnapshot
import com.thecryptosteer.xmrminer.domain.MiningState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val FULL_MODE_RESERVE_BYTES = 2_684_354_560L
private val devicePagePadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)

@Composable
fun DeviceScreen(
    state: DeviceInfoUiState,
    miningSnapshot: MiningSnapshot,
    settings: AppSettings,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) {
        onRefresh()
        while (isActive) {
            delay(5_000L)
            onRefresh()
        }
    }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val info = state.snapshot
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = devicePagePadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "Device",
                subtitle = "Live, non-identifying hardware and Android runtime facts that affect RandomX mining.",
                action = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        if (state.loading && info != null) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, "Refresh device information")
                        }
                    }
                },
            )
        }

        if (state.loading && info == null) {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Reading device capabilities", style = MaterialTheme.typography.titleMedium)
                            Text("Collecting CPU topology, memory, native features, power, and thermal state.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (state.error.isNotBlank()) {
            item {
                InlineBanner(
                    title = "Some device information is unavailable",
                    message = state.error,
                    tone = StatusTone.WARNING,
                    actionLabel = "Retry",
                    onAction = onRefresh,
                )
            }
        }

        if (info != null) {
            item { MiningReadinessCard(info, miningSnapshot, settings) }
            item { LiveDeviceHealthCard(info, miningSnapshot) }
            item { CpuTopologyCard(info, miningSnapshot) }
            item { MemoryAndRandomXCard(info, miningSnapshot, settings) }
            item { NativeCapabilitiesCard(info, miningSnapshot) }
            item { DevicePlatformCard(info) }
            item {
                val guidance = remember(info, miningSnapshot, settings) { miningGuidance(info, miningSnapshot, settings) }
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader("Mining guidance", "Based only on device state; benchmark results remain the performance authority")
                        guidance.forEachIndexed { index, message ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (info.collectionNotes.isNotEmpty()) {
                item {
                    ExpandableTechnicalDetails(
                        title = "Collection notes",
                        summary = "${info.collectionNotes.size} best-effort limitation${if (info.collectionNotes.size == 1) "" else "s"}",
                    ) {
                        info.collectionNotes.forEach { note -> Text("• $note", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item {
                SecondaryActionButton(
                    label = "Copy sanitized device report",
                    icon = Icons.Outlined.ContentCopy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        clipboard.setText(AnnotatedString(deviceInfoReport(info)))
                        Toast.makeText(context, "Device report copied", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            item {
                InlineBanner(
                    title = "Privacy boundary",
                    message = "This screen does not read or report serial numbers, IMEI, advertising identifiers, network addresses, account data, wallet credentials, or files outside the app and public Linux/Android capability interfaces.",
                    tone = StatusTone.LIVE,
                )
            }
        }
    }
}

@Composable
private fun MiningReadinessCard(info: DeviceInfoSnapshot, mining: MiningSnapshot, settings: AppSettings) {
    val fullModeHeadroom = info.availableMemoryBytes - FULL_MODE_RESERVE_BYTES
    val memoryTone = when {
        info.lowMemory || fullModeHeadroom < 0L -> StatusTone.WARNING
        fullModeHeadroom >= 1_073_741_824L -> StatusTone.HEALTHY
        else -> StatusTone.LIVE
    }
    val jit = mining.jitEnabled || info.jitAvailable == true
    val aes = mining.hardwareAes || info.hardwareAesAvailable == true
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().monitoringPanel(MaterialTheme.colorScheme.primary),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${info.manufacturer} ${info.model}", style = MaterialTheme.typography.headlineSmall)
                    Text("${info.socManufacturer} ${info.socModel}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.DeveloperBoard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("${info.allowedCpuIds.size} app CPUs", if (info.allowedCpuIds.isNotEmpty()) StatusTone.HEALTHY else StatusTone.WARNING, icon = Icons.Outlined.Memory)
                StatusChip("${formatBinaryBytes(info.availableMemoryBytes)} free", memoryTone, icon = Icons.Outlined.Storage)
                StatusChip(if (jit) "JIT available" else "JIT unavailable", if (jit) StatusTone.PERFORMANCE else StatusTone.WARNING, icon = Icons.Outlined.Speed)
                StatusChip(if (aes) "Hardware AES" else "AES unavailable", if (aes) StatusTone.PERFORMANCE else StatusTone.WARNING, icon = Icons.Outlined.Security)
            }
            DefinitionRow("Selected power profile", settings.tuning.powerProfile.name.lowercase().replaceFirstChar(Char::uppercase))
            DefinitionRow("Configured workers", if (settings.tuning.threadCount == 0) "Automatic (${settings.tuning.cpuUtilizationPercent}% CPU target)" else settings.tuning.threadCount.toString())
            DefinitionRow("Current workers", mining.workers.toString(), tone = if (mining.workers > 0) StatusTone.LIVE else StatusTone.NEUTRAL)
            DefinitionRow("Current RandomX mode", mining.randomXMode.ifBlank { settings.tuning.randomXMode.name.lowercase().replaceFirstChar(Char::uppercase) })
            DefinitionRow("Full-mode free-memory margin", if (fullModeHeadroom >= 0L) formatBinaryBytes(fullModeHeadroom) else "Short by ${formatBinaryBytes(-fullModeHeadroom)}", tone = memoryTone)
            Text(
                "The memory margin uses a conservative 2.5 GiB reservation for the RandomX dataset and working overhead. Actual viability is verified by Benchmark or Auto-tune.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveDeviceHealthCard(info: DeviceInfoSnapshot, mining: MiningSnapshot) {
    val thermal = maxOf(info.thermalStatus, mining.thermalStatus)
    val thermalTone = when {
        thermal >= 5 -> StatusTone.CRITICAL
        thermal >= 3 -> StatusTone.WARNING
        thermal >= 1 -> StatusTone.LIVE
        else -> StatusTone.HEALTHY
    }
    val headroomText = info.thermalHeadroom?.let { "%.2f".format(Locale.US, it) } ?: "Unavailable"
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionHeader("Live device health", "Updated every five seconds while this tab is visible")
            CompactMetricRow("Android thermal state", thermalLabel(thermal), icon = Icons.Outlined.Thermostat, tone = thermalTone, supporting = thermalHeadroomGuidance(info.thermalHeadroom))
            CompactMetricRow("Thermal headroom", headroomText, icon = Icons.Outlined.Speed, tone = thermalHeadroomTone(info.thermalHeadroom), supporting = "1.00 is approximately Android's severe-throttling threshold")
            CompactMetricRow("Battery", "${info.batteryPercent}%", icon = Icons.Outlined.BatteryChargingFull, tone = if (info.charging) StatusTone.HEALTHY else StatusTone.NEUTRAL, supporting = "${if (info.charging) "Charging" else "Not charging"} • ${info.pluggedSource} • ${info.batteryHealth}")
            CompactMetricRow("Battery temperature", info.batteryTemperatureCelsius?.let { "%.1f °C".format(Locale.US, it) } ?: "Unavailable", icon = Icons.Outlined.Thermostat, tone = batteryTemperatureTone(info.batteryTemperatureCelsius), supporting = info.batteryVoltageMillivolts?.let { "$it mV" })
            CompactMetricRow("Power saver", if (info.powerSaveMode) "Enabled" else "Disabled", icon = Icons.Outlined.BatterySaver, tone = if (info.powerSaveMode) StatusTone.WARNING else StatusTone.HEALTHY, supporting = if (info.powerSaveMode) "Android may reduce sustained background performance" else "No system power-saver restriction reported")
            DefinitionRow("Battery optimization exemption", if (info.batteryOptimizationExempt) "Exempt" else "Standard Android policy")
            DefinitionRow("Sustained-performance feature", if (info.sustainedPerformanceSupported) "Supported" else "Not advertised")
        }
    }
}

@Composable
private fun CpuTopologyCard(info: DeviceInfoSnapshot, mining: MiningSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("CPU topology", "Android cpuset limits and per-core frequency data used for worker placement")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip("${info.possibleCpuIds.size} possible", StatusTone.NEUTRAL)
                    StatusChip("${info.onlineCpuIds.size} online", StatusTone.HEALTHY)
                    StatusChip("${info.allowedCpuIds.size} allowed", if (info.allowedCpuIds.size < info.onlineCpuIds.size) StatusTone.WARNING else StatusTone.LIVE)
                    StatusChip("${info.appAvailableProcessors} JVM visible", StatusTone.NEUTRAL)
                }
                info.cpuClusters.forEach { cluster ->
                    DefinitionRow(
                        cluster.label,
                        "CPU ${formatCpuIds(cluster.coreIds)} • ${formatFrequency(cluster.maximumFrequencyKHz)}${cluster.capacity?.let { " • capacity $it" }.orEmpty()}",
                        tone = if (cluster.label == "Prime" || cluster.label == "Performance") StatusTone.PERFORMANCE else StatusTone.NEUTRAL,
                    )
                }
                DefinitionRow("Native recommended order", formatCpuIds(info.recommendedCpuIds), tone = StatusTone.PERFORMANCE)
                if (mining.cpuAssignments.isNotEmpty()) {
                    DefinitionRow("Active worker assignment", formatCpuIds(mining.cpuAssignments), tone = StatusTone.LIVE)
                }
            }
        }

        ExpandableTechnicalDetails(
            title = "Per-core details",
            summary = "${info.cpuCores.size} logical cores",
        ) {
            if (info.cpuCores.isEmpty()) {
                Text("No per-core information was exposed by Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                info.cpuCores.forEachIndexed { index, core ->
                    CpuCoreRow(core)
                    if (index != info.cpuCores.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun CpuCoreRow(core: CpuCoreInfo) {
    val tone = when {
        !core.online -> StatusTone.CRITICAL
        !core.allowed -> StatusTone.WARNING
        core.cluster == "Prime" || core.cluster == "Performance" -> StatusTone.PERFORMANCE
        else -> StatusTone.NEUTRAL
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("CPU ${core.logicalIndex} • ${core.cluster}", style = MaterialTheme.typography.titleSmall)
            StatusChip(
                when {
                    !core.online -> "Offline"
                    !core.allowed -> "Outside app cpuset"
                    else -> "Available"
                },
                tone,
            )
        }
        Text(
            "Current ${formatFrequency(core.currentFrequencyKHz)} • min ${formatFrequency(core.minimumFrequencyKHz)} • max ${formatFrequency(core.maximumFrequencyKHz)}${core.capacity?.let { " • capacity $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryAndRandomXCard(info: DeviceInfoSnapshot, mining: MiningSnapshot, settings: AppSettings) {
    val availablePercent = if (info.totalMemoryBytes > 0L) info.availableMemoryBytes * 100.0 / info.totalMemoryBytes else 0.0
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionHeader("Memory and RandomX", "Physical-memory pressure matters more than Android's managed app heap")
            DefinitionRow("Physical memory", formatBinaryBytes(info.totalMemoryBytes))
            DefinitionRow("Available now", "${formatBinaryBytes(info.availableMemoryBytes)} (${"%.1f".format(Locale.US, availablePercent)}%)", tone = if (info.lowMemory) StatusTone.CRITICAL else StatusTone.HEALTHY)
            DefinitionRow("Android low-memory threshold", formatBinaryBytes(info.lowMemoryThresholdBytes))
            DefinitionRow("Low-memory state", if (info.lowMemory) "Active" else "Not active", tone = if (info.lowMemory) StatusTone.CRITICAL else StatusTone.HEALTHY)
            DefinitionRow("Low-RAM device classification", if (info.lowRamDevice) "Yes" else "No")
            DefinitionRow("Managed heap maximum", formatBinaryBytes(info.appHeapMaximumBytes))
            DefinitionRow("Managed heap allocated", formatBinaryBytes(info.appHeapAllocatedBytes))
            DefinitionRow("Android memory class", "${info.memoryClassMiB} MiB • large ${info.largeMemoryClassMiB} MiB")
            DefinitionRow("Configured memory mode", settings.tuning.randomXMode.name.lowercase().replaceFirstChar(Char::uppercase))
            DefinitionRow("Active memory mode", mining.randomXMode.ifBlank { "Not initialized" })
            DefinitionRow("Dataset preparation", if (mining.datasetProgress >= 100) "Ready" else "${mining.datasetProgress}%")
            DefinitionRow("Visible huge pages", info.hugePagesTotal?.toString() ?: "Unavailable")
            DefinitionRow("Huge-page size", info.hugePageSizeBytes?.let(::formatBinaryBytes) ?: "Unavailable")
        }
    }
}

@Composable
private fun NativeCapabilitiesCard(info: DeviceInfoSnapshot, mining: MiningSnapshot) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionHeader("Native mining capabilities", "Read from the same CryptoSteer native library used by the miner")
            DefinitionRow("Native engine", info.nativeVersion)
            DefinitionRow("Native topology", info.nativeCpuTopology)
            DefinitionRow("64-bit ABI", info.supported64BitAbis.joinToString().ifBlank { "Unavailable" })
            DefinitionRow("RandomX capability flags", info.randomXFlags?.toString() ?: "Unavailable")
            DefinitionRow("JIT capability", capabilityLabel(info.jitAvailable), tone = capabilityTone(info.jitAvailable))
            DefinitionRow("JIT active this session", if (mining.jitEnabled) "Yes" else "No or not initialized", tone = if (mining.jitEnabled) StatusTone.PERFORMANCE else StatusTone.NEUTRAL)
            DefinitionRow("Hardware AES capability", capabilityLabel(info.hardwareAesAvailable), tone = capabilityTone(info.hardwareAesAvailable))
            DefinitionRow("Hardware AES active", if (mining.hardwareAes) "Yes" else "No or not initialized", tone = if (mining.hardwareAes) StatusTone.PERFORMANCE else StatusTone.NEUTRAL)
            DefinitionRow("Runtime page size", info.runtimePageSizeBytes?.let(::formatBinaryBytes) ?: "Unavailable", tone = if (info.runtimePageSizeSupported == true) StatusTone.HEALTHY else StatusTone.WARNING)
            DefinitionRow("Page size supported", capabilityLabel(info.runtimePageSizeSupported), tone = capabilityTone(info.runtimePageSizeSupported))
            DefinitionRow("libuv", info.libuvVersion)
            DefinitionRow("OpenSSL", info.opensslVersion)
            DefinitionRow("hwloc API", info.hwlocApiVersion?.toString() ?: "Unavailable")
        }
    }
}

@Composable
private fun DevicePlatformCard(info: DeviceInfoSnapshot) {
    ExpandableTechnicalDetails(
        title = "Android and platform",
        summary = "Android ${info.androidRelease} • API ${info.apiLevel} • ${info.supportedAbis.firstOrNull() ?: "unknown ABI"}",
    ) {
        DefinitionRow("Manufacturer", info.manufacturer)
        DefinitionRow("Brand", info.brand)
        DefinitionRow("Model", info.model)
        DefinitionRow("Product", info.product)
        DefinitionRow("Device", info.device)
        DefinitionRow("Hardware", info.hardware)
        DefinitionRow("SoC manufacturer", info.socManufacturer)
        DefinitionRow("SoC model", info.socModel)
        DefinitionRow("Android", "${info.androidRelease} • API ${info.apiLevel}")
        DefinitionRow("Security patch", info.securityPatch)
        DefinitionRow("Build ID", info.buildId)
        DefinitionRow("Kernel", info.kernelVersion)
        DefinitionRow("Supported ABIs", info.supportedAbis.joinToString().ifBlank { "Unavailable" })
        DefinitionRow("App storage available", formatBinaryBytes(info.storageAvailableBytes))
        DefinitionRow("App storage volume", formatBinaryBytes(info.storageTotalBytes))
        DefinitionRow("Last refreshed", DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(info.collectedAtMillis)))
    }
}

internal fun miningGuidance(info: DeviceInfoSnapshot, mining: MiningSnapshot, settings: AppSettings): List<String> = buildList {
    add("Run the local Benchmark and five-minute Auto-tune before choosing final thread or core settings; specifications alone do not prove sustained hashrate.")
    if (info.allowedCpuIds.size < info.onlineCpuIds.size) {
        add("Android currently allows this app to use ${info.allowedCpuIds.size} of ${info.onlineCpuIds.size} online CPUs. CryptoSteer respects that cpuset instead of attempting to escape system scheduling policy.")
    } else {
        add("All ${info.allowedCpuIds.size} reported online CPUs are currently inside the app's allowed CPU mask.")
    }
    when {
        info.lowMemory -> add("Android reports active low-memory pressure. Stop other heavy apps and prefer Auto or Light mode until memory pressure clears.")
        info.availableMemoryBytes < FULL_MODE_RESERVE_BYTES -> add("Available physical memory is below the conservative Full-mode reservation. Auto or Light mode is the safer current choice.")
        else -> add("Current free memory leaves ${formatBinaryBytes(info.availableMemoryBytes - FULL_MODE_RESERVE_BYTES)} beyond the conservative Full-mode reservation; verify Full mode with Benchmark before pool mining.")
    }
    if (maxOf(info.thermalStatus, mining.thermalStatus) >= 3 || (info.thermalHeadroom ?: 0f) >= 0.8f) {
        add("Thermal pressure is elevated. Improve airflow, remove an insulating case, reduce workers, or allow the configured thermal pause to protect sustained performance.")
    } else {
        add("No severe thermal pressure is currently reported, but sustained mining can change this quickly.")
    }
    if (info.powerSaveMode) add("System power saver is enabled and may reduce background CPU performance or network consistency.")
    if (settings.tuning.mineOnlyWhileCharging && !info.charging) add("The selected policy requires charging, so mining will remain paused until external power is connected.")
    if (info.jitAvailable == false) add("RandomX JIT is unavailable according to the native capability check; the interpreter path is safe but will usually be slower.")
    if (info.hardwareAesAvailable == false) add("Hardware AES is unavailable according to RandomX capability detection, which can materially reduce hashrate.")
    if (mining.state == MiningState.MINING_USER || mining.state == MiningState.MINING_DONATION) {
        add("The current session is using ${mining.workers} workers on CPUs ${formatCpuIds(mining.cpuAssignments)} at ${"%.2f H/s".format(Locale.US, mining.hashrate)}.")
    }
}

private fun thermalHeadroomGuidance(value: Float?): String = when {
    value == null -> "Android did not provide a forecast"
    value >= 1.0f -> "At or beyond the forecast severe-throttling threshold"
    value >= 0.8f -> "Close to severe thermal throttling"
    value >= 0.5f -> "Moderate thermal pressure"
    else -> "Comfortable forecast margin"
}

private fun thermalHeadroomTone(value: Float?): StatusTone = when {
    value == null -> StatusTone.NEUTRAL
    value >= 1.0f -> StatusTone.CRITICAL
    value >= 0.8f -> StatusTone.WARNING
    value >= 0.5f -> StatusTone.LIVE
    else -> StatusTone.HEALTHY
}

private fun batteryTemperatureTone(value: Float?): StatusTone = when {
    value == null -> StatusTone.NEUTRAL
    value >= 48f -> StatusTone.CRITICAL
    value >= 42f -> StatusTone.WARNING
    value >= 36f -> StatusTone.LIVE
    else -> StatusTone.HEALTHY
}

private fun capabilityLabel(value: Boolean?): String = when (value) {
    true -> "Available"
    false -> "Unavailable"
    null -> "Not reported"
}

private fun capabilityTone(value: Boolean?): StatusTone = when (value) {
    true -> StatusTone.HEALTHY
    false -> StatusTone.WARNING
    null -> StatusTone.NEUTRAL
}
