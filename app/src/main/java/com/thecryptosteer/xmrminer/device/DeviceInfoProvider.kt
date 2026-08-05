package com.thecryptosteer.xmrminer.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.thecryptosteer.xmrminer.nativebridge.NativeMiner
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_CPU_INDEX = 1023
private const val KIB = 1024L
private const val MIB = KIB * 1024L
private const val GIB = MIB * 1024L

/** A best-effort snapshot of non-identifying hardware and runtime facts relevant to RandomX. */
data class DeviceInfoSnapshot(
    val collectedAtMillis: Long,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val product: String,
    val device: String,
    val hardware: String,
    val socManufacturer: String,
    val socModel: String,
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: String,
    val buildId: String,
    val kernelVersion: String,
    val supportedAbis: List<String>,
    val supported64BitAbis: List<String>,
    val appAvailableProcessors: Int,
    val possibleCpuIds: List<Int>,
    val onlineCpuIds: List<Int>,
    val allowedCpuIds: List<Int>,
    val recommendedCpuIds: List<Int>,
    val cpuCores: List<CpuCoreInfo>,
    val cpuClusters: List<CpuClusterInfo>,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val lowMemory: Boolean,
    val lowRamDevice: Boolean,
    val memoryClassMiB: Int,
    val largeMemoryClassMiB: Int,
    val appHeapMaximumBytes: Long,
    val appHeapAllocatedBytes: Long,
    val appHeapFreeBytes: Long,
    val hugePagesTotal: Long?,
    val hugePageSizeBytes: Long?,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val powerSaveMode: Boolean,
    val batteryOptimizationExempt: Boolean,
    val sustainedPerformanceSupported: Boolean,
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTemperatureCelsius: Float?,
    val batteryVoltageMillivolts: Int?,
    val batteryHealth: String,
    val pluggedSource: String,
    val nativeVersion: String,
    val nativeCpuTopology: String,
    val randomXFlags: Long?,
    val jitAvailable: Boolean?,
    val hardwareAesAvailable: Boolean?,
    val runtimePageSizeBytes: Long?,
    val runtimePageSizeSupported: Boolean?,
    val libuvVersion: String,
    val opensslVersion: String,
    val hwlocApiVersion: Long?,
    val collectionNotes: List<String>,
)

data class CpuCoreInfo(
    val logicalIndex: Int,
    val online: Boolean,
    val allowed: Boolean,
    val capacity: Long?,
    val minimumFrequencyKHz: Long?,
    val currentFrequencyKHz: Long?,
    val maximumFrequencyKHz: Long?,
    val cluster: String,
)

data class CpuClusterInfo(
    val label: String,
    val coreIds: List<Int>,
    val capacity: Long?,
    val maximumFrequencyKHz: Long?,
)

data class DeviceInfoUiState(
    val loading: Boolean = true,
    val snapshot: DeviceInfoSnapshot? = null,
    val error: String = "",
)

class DeviceInfoProvider(private val context: Context) {
    fun collect(): DeviceInfoSnapshot {
        val notes = mutableListOf<String>()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val nativeJson = runCatching { JSONObject(NativeMiner.nativeVersion()) }
            .onFailure { notes += "Native capability details were unavailable: ${safeMessage(it)}" }
            .getOrNull()

        val nativeAllowed = nativeJson?.optJSONArray("allowedCpus").toIntList()
        val nativeRecommended = nativeJson?.optJSONArray("recommendedCpus").toIntList()
        val nativeCpuDetails = nativeJson?.optJSONArray("cpuDetails").toNativeCpuDetails()
        val possible = readCpuList("/sys/devices/system/cpu/possible")
        val present = readCpuList("/sys/devices/system/cpu/present")
        val online = readCpuList("/sys/devices/system/cpu/online")
        val procAllowed = readAllowedCpuListFromProcStatus()
        val fallbackIds = (0 until runtime.availableProcessors().coerceAtLeast(1)).toList()
        val possibleIds = firstNotEmpty(possible, present, online, nativeAllowed, fallbackIds)
        val onlineIds = firstNotEmpty(online, nativeAllowed, possibleIds)
        val allowedIds = firstNotEmpty(procAllowed, nativeAllowed, onlineIds)
        val coreIds = (possibleIds + onlineIds + allowedIds).distinct().sorted().filter { it in 0..MAX_CPU_INDEX }
        val rawCores = coreIds.map { cpu ->
            CpuCoreInfo(
                logicalIndex = cpu,
                online = cpu in onlineIds,
                allowed = cpu in allowedIds,
                capacity = readLong("/sys/devices/system/cpu/cpu$cpu/cpu_capacity")
                    ?: nativeCpuDetails[cpu]?.capacity,
                minimumFrequencyKHz = readFirstLong(
                    "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_min_freq",
                    "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_min_freq",
                ),
                currentFrequencyKHz = readFirstLong(
                    "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq",
                    "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_cur_freq",
                ),
                maximumFrequencyKHz = readFirstLong(
                    "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq",
                    "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq",
                ) ?: nativeCpuDetails[cpu]?.maximumFrequencyKHz,
                cluster = "",
            )
        }
        val clusters = buildCpuClusters(rawCores)
        val clusterByCpu = clusters.flatMap { cluster -> cluster.coreIds.map { it to cluster.label } }.toMap()
        val cores = rawCores.map { it.copy(cluster = clusterByCpu[it.logicalIndex] ?: "Unclassified") }

        if (cores.none { it.maximumFrequencyKHz != null }) {
            notes += "Android did not expose per-core frequency files on this device."
        }
        if (procAllowed.isEmpty() && nativeAllowed.isEmpty()) {
            notes += "The process CPU affinity mask could not be read; online CPUs are shown as the fallback."
        }

        val batteryIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val batteryPercent = batteryIntent?.batteryPercent()
            ?: batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            ?: 0
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val memInfo = readMemInfo()
        val storage = StatFs(context.filesDir.absolutePath)
        val thermalHeadroom = runCatching { powerManager.getThermalHeadroom(0) }
            .getOrNull()
            ?.takeUnless { it.isNaN() || it.isInfinite() || it < 0f }

        return DeviceInfoSnapshot(
            collectedAtMillis = System.currentTimeMillis(),
            manufacturer = cleanBuildValue(Build.MANUFACTURER),
            brand = cleanBuildValue(Build.BRAND),
            model = cleanBuildValue(Build.MODEL),
            product = cleanBuildValue(Build.PRODUCT),
            device = cleanBuildValue(Build.DEVICE),
            hardware = cleanBuildValue(Build.HARDWARE),
            socManufacturer = cleanBuildValue(Build.SOC_MANUFACTURER),
            socModel = cleanBuildValue(Build.SOC_MODEL),
            androidRelease = Build.VERSION.RELEASE.ifBlank { "Unknown" },
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.ifBlank { "Unknown" },
            buildId = Build.ID.ifBlank { "Unknown" },
            kernelVersion = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            supported64BitAbis = Build.SUPPORTED_64_BIT_ABIS.toList(),
            appAvailableProcessors = runtime.availableProcessors(),
            possibleCpuIds = possibleIds,
            onlineCpuIds = onlineIds,
            allowedCpuIds = allowedIds,
            recommendedCpuIds = firstNotEmpty(nativeRecommended, cores.filter { it.allowed }.sortedWith(corePerformanceComparator()).map { it.logicalIndex }),
            cpuCores = cores,
            cpuClusters = clusters,
            totalMemoryBytes = memoryInfo.totalMem,
            availableMemoryBytes = memoryInfo.availMem,
            lowMemoryThresholdBytes = memoryInfo.threshold,
            lowMemory = memoryInfo.lowMemory,
            lowRamDevice = activityManager.isLowRamDevice,
            memoryClassMiB = activityManager.memoryClass,
            largeMemoryClassMiB = activityManager.largeMemoryClass,
            appHeapMaximumBytes = runtime.maxMemory(),
            appHeapAllocatedBytes = runtime.totalMemory(),
            appHeapFreeBytes = runtime.freeMemory(),
            hugePagesTotal = memInfo["HugePages_Total"],
            hugePageSizeBytes = memInfo["Hugepagesize"]?.times(KIB),
            storageTotalBytes = storage.totalBytes,
            storageAvailableBytes = storage.availableBytes,
            thermalStatus = powerManager.currentThermalStatus,
            thermalHeadroom = thermalHeadroom,
            powerSaveMode = powerManager.isPowerSaveMode,
            batteryOptimizationExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            sustainedPerformanceSupported = powerManager.isSustainedPerformanceModeSupported(),
            batteryPercent = batteryPercent,
            charging = charging,
            batteryTemperatureCelsius = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE && it > -1_000 && it < 2_000 }
                ?.div(10f),
            batteryVoltageMillivolts = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
                ?.takeIf { it in 1..20_000 },
            batteryHealth = batteryHealthLabel(batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)),
            pluggedSource = pluggedSourceLabel(batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0),
            nativeVersion = nativeJson?.optString("nativeVersion").orEmpty().ifBlank { "Unavailable" },
            nativeCpuTopology = nativeJson?.optString("cpuTopology").orEmpty().ifBlank { "Unavailable" },
            randomXFlags = nativeJson?.optLongOrNull("randomXFlags"),
            jitAvailable = nativeJson?.optBooleanOrNull("jitAvailable"),
            hardwareAesAvailable = nativeJson?.optBooleanOrNull("hardwareAesAvailable"),
            runtimePageSizeBytes = nativeJson?.optLongOrNull("pageSize"),
            runtimePageSizeSupported = nativeJson?.optBooleanOrNull("pageSizeSupported"),
            libuvVersion = nativeJson?.optString("libuv").orEmpty().ifBlank { "Unavailable" },
            opensslVersion = nativeJson?.optString("openssl").orEmpty().ifBlank { "Unavailable" },
            hwlocApiVersion = nativeJson?.optLongOrNull("hwlocApi"),
            collectionNotes = notes.distinct(),
        )
    }
}

internal fun parseCpuList(raw: String): List<Int> {
    if (raw.isBlank()) return emptyList()
    val values = linkedSetOf<Int>()
    raw.trim().split(',').forEach { token ->
        val part = token.trim()
        if (part.isEmpty()) return@forEach
        val range = part.split('-', limit = 2)
        val start = range[0].toIntOrNull() ?: return@forEach
        val end = if (range.size == 2) range[1].toIntOrNull() ?: return@forEach else start
        if (start !in 0..MAX_CPU_INDEX || end !in start..MAX_CPU_INDEX) return@forEach
        for (value in start..end) values += value
    }
    return values.sorted()
}

internal fun buildCpuClusters(cores: List<CpuCoreInfo>): List<CpuClusterInfo> {
    if (cores.isEmpty()) return emptyList()
    val groups = cores.groupBy { core ->
        val capacity = core.capacity ?: -1L
        val maximum = core.maximumFrequencyKHz ?: -1L
        capacity to maximum
    }.toList().sortedWith(compareBy({ it.first.first }, { it.first.second }))
    val labels = when (groups.size) {
        1 -> listOf("Uniform")
        2 -> listOf("Efficiency", "Performance")
        3 -> listOf("Efficiency", "Performance", "Prime")
        else -> groups.indices.map { "Cluster ${it + 1}" }
    }
    return groups.mapIndexed { index, (key, groupedCores) ->
        CpuClusterInfo(
            label = labels[index],
            coreIds = groupedCores.map { it.logicalIndex }.sorted(),
            capacity = key.first.takeIf { it >= 0L },
            maximumFrequencyKHz = key.second.takeIf { it >= 0L },
        )
    }
}

internal fun formatBinaryBytes(bytes: Long): String {
    if (bytes < 0L) return "Unavailable"
    val value = when {
        bytes >= GIB -> bytes.toDouble() / GIB to "GiB"
        bytes >= MIB -> bytes.toDouble() / MIB to "MiB"
        bytes >= KIB -> bytes.toDouble() / KIB to "KiB"
        else -> bytes.toDouble() to "bytes"
    }
    val decimals = if (value.first >= 100.0 || value.second == "bytes") 0 else 1
    return "%.${decimals}f %s".format(Locale.US, value.first, value.second)
}

internal fun formatFrequency(khz: Long?): String = when {
    khz == null || khz <= 0L -> "Unavailable"
    khz >= 1_000_000L -> "%.2f GHz".format(Locale.US, khz / 1_000_000.0)
    else -> "%.0f MHz".format(Locale.US, khz / 1_000.0)
}

internal fun deviceInfoReport(info: DeviceInfoSnapshot): String = buildString {
    appendLine("CryptoSteer device report")
    appendLine("Collected: ${info.collectedAtMillis}")
    appendLine("Device: ${info.manufacturer} ${info.model}")
    appendLine("SoC: ${info.socManufacturer} ${info.socModel}")
    appendLine("Android: ${info.androidRelease} (API ${info.apiLevel}), patch ${info.securityPatch}")
    appendLine("Kernel: ${info.kernelVersion}")
    appendLine("ABIs: ${info.supportedAbis.joinToString()}")
    appendLine("CPUs: possible=${formatCpuIds(info.possibleCpuIds)}, online=${formatCpuIds(info.onlineCpuIds)}, allowed=${formatCpuIds(info.allowedCpuIds)}")
    appendLine("Recommended order: ${formatCpuIds(info.recommendedCpuIds)}")
    info.cpuClusters.forEach { cluster ->
        appendLine("${cluster.label}: CPUs ${formatCpuIds(cluster.coreIds)}, max ${formatFrequency(cluster.maximumFrequencyKHz)}, capacity ${cluster.capacity ?: "unavailable"}")
    }
    appendLine("Memory: ${formatBinaryBytes(info.totalMemoryBytes)} total, ${formatBinaryBytes(info.availableMemoryBytes)} available, low=${info.lowMemory}")
    appendLine("App heap: ${formatBinaryBytes(info.appHeapMaximumBytes)} maximum")
    appendLine("Thermal: status=${info.thermalStatus}, headroom=${info.thermalHeadroom ?: "unavailable"}")
    appendLine("Battery: ${info.batteryPercent}%, charging=${info.charging}, temperature=${info.batteryTemperatureCelsius ?: "unavailable"} C")
    appendLine("Power saver: ${info.powerSaveMode}")
    appendLine("Native: ${info.nativeVersion}, topology=${info.nativeCpuTopology}")
    appendLine("RandomX flags: ${info.randomXFlags ?: "unavailable"}, JIT=${info.jitAvailable ?: "unavailable"}, hardware AES=${info.hardwareAesAvailable ?: "unavailable"}")
    appendLine("Runtime page size: ${info.runtimePageSizeBytes ?: "unavailable"}, supported=${info.runtimePageSizeSupported ?: "unavailable"}")
    appendLine("Libraries: libuv=${info.libuvVersion}, OpenSSL=${info.opensslVersion}, hwloc API=${info.hwlocApiVersion ?: "unavailable"}")
    if (info.collectionNotes.isNotEmpty()) appendLine("Notes: ${info.collectionNotes.joinToString(" | ")}")
    append("This report excludes serial numbers, advertising identifiers, network addresses, account data, and wallet credentials.")
}

internal fun formatCpuIds(ids: List<Int>): String = if (ids.isEmpty()) "Unavailable" else ids.joinToString(", ")

private fun readCpuList(path: String): List<Int> = runCatching { parseCpuList(File(path).readText()) }.getOrDefault(emptyList())

private fun readAllowedCpuListFromProcStatus(): List<Int> = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')
            ?.let(::parseCpuList)
            .orEmpty()
    }
}.getOrDefault(emptyList())

private fun readLong(path: String): Long? = runCatching {
    File(path).readText().trim().toLong()
}.getOrNull()?.takeIf { it >= 0L }

private fun readFirstLong(vararg paths: String): Long? = paths.firstNotNullOfOrNull(::readLong)

private fun readMemInfo(): Map<String, Long> = runCatching {
    File("/proc/meminfo").useLines { lines ->
        lines.mapNotNull { line ->
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim().substringBefore(' ').toLongOrNull()
            if (key.isBlank() || value == null) null else key to value
        }.toMap()
    }
}.getOrDefault(emptyMap())

private fun corePerformanceComparator(): Comparator<CpuCoreInfo> = compareByDescending<CpuCoreInfo> { it.capacity ?: -1L }
    .thenByDescending { it.maximumFrequencyKHz ?: -1L }
    .thenBy { it.logicalIndex }

private fun firstNotEmpty(vararg values: List<Int>): List<Int> = values.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun cleanBuildValue(value: String): String = value.trim().takeUnless { it.isBlank() || it.equals(Build.UNKNOWN, true) } ?: "Unknown"

private fun batteryHealthLabel(value: Int?): String = when (value) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
    else -> "Unknown"
}

private fun pluggedSourceLabel(value: Int): String = when {
    value and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC charger"
    value and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB charger"
    value and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Wireless charger"
    value and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> "Dock"
    else -> "Battery"
}

private fun Intent.batteryPercent(): Int? {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    return ((level * 100.0) / scale).toInt().coerceIn(0, 100)
}


private data class NativeCpuDetail(
    val capacity: Long?,
    val maximumFrequencyKHz: Long?,
)

private fun JSONArray?.toNativeCpuDetails(): Map<Int, NativeCpuDetail> {
    if (this == null) return emptyMap()
    return buildMap {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val cpu = item.optInt("index", -1)
            if (cpu !in 0..MAX_CPU_INDEX) continue
            put(
                cpu,
                NativeCpuDetail(
                    capacity = item.optLong("capacity", -1L).takeIf { it >= 0L },
                    maximumFrequencyKHz = item.optLong("maxFrequencyKHz", -1L).takeIf { it > 0L },
                ),
            )
        }
    }
}

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optInt(index, -1)
            if (value in 0..MAX_CPU_INDEX) add(value)
        }
    }.distinct()
}

private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
private fun JSONObject.optBooleanOrNull(name: String): Boolean? = if (has(name) && !isNull(name)) optBoolean(name) else null
private fun safeMessage(throwable: Throwable): String = throwable.message?.take(180)?.replace(Regex("[\r\n]+"), " ") ?: throwable::class.java.simpleName
