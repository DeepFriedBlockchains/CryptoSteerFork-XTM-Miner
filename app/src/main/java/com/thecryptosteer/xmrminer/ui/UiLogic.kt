package com.thecryptosteer.xmrminer.ui

import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.MiningState

enum class ChartRange(val label: String, val windowMillis: Long?) {
    LIVE("Live", 60_000L),
    FIVE_MINUTES("5 min", 5L * 60_000L),
    FIFTEEN_MINUTES("15 min", 15L * 60_000L),
    ONE_HOUR("1 hr", 60L * 60_000L),
    SESSION("Session", null),
}

data class HashrateChartWindow(
    val points: List<HashratePoint>,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(1L)
}

fun chartWindow(points: List<HashratePoint>, range: ChartRange, nowMillis: Long): HashrateChartWindow {
    val ordered = points.sortedBy { it.timeMillis }
    val end = maxOf(nowMillis, ordered.lastOrNull()?.timeMillis ?: nowMillis)
    val start = when (val duration = range.windowMillis) {
        null -> ordered.firstOrNull()?.timeMillis ?: end - 1L
        else -> end - duration
    }
    return HashrateChartWindow(ordered.filter { it.timeMillis in start..end }, start, end)
}

fun compactHashrateHistory(points: List<HashratePoint>, maximumPoints: Int): List<HashratePoint> {
    if (points.size <= maximumPoints || maximumPoints < 8) return points

    val ordered = points.sortedBy { it.timeMillis }
    val recentCount = (maximumPoints / 2).coerceAtLeast(2)
    val recent = ordered.takeLast(recentCount)
    var older = ordered.dropLast(recentCount)

    while (older.size + recent.size > maximumPoints && older.size > 1) {
        val oldest = older.first()
        val compactedTail = older.drop(1).chunked(2).map { bucket ->
            if (bucket.size == 1) {
                bucket.first()
            } else {
                HashratePoint(
                    timeMillis = bucket.last().timeMillis,
                    hashrate = bucket.sumOf { it.hashrate } / bucket.size,
                    thermalStatus = bucket.maxOf { it.thermalStatus },
                    batteryPercent = bucket.last().batteryPercent,
                )
            }
        }
        older = listOf(oldest) + compactedTail
    }

    val olderCapacity = (maximumPoints - recent.size).coerceAtLeast(1)
    val boundedOlder = if (older.size <= olderCapacity) older else decimatePoints(older, olderCapacity)
    return (boundedOlder + recent)
        .distinctBy { it.timeMillis }
        .sortedBy { it.timeMillis }
        .takeLast(maximumPoints)
        .let { bounded ->
            if (bounded.firstOrNull()?.timeMillis == ordered.first().timeMillis) {
                bounded
            } else {
                listOf(ordered.first()) + bounded.drop(1)
            }
        }
}

fun decimatePoints(points: List<HashratePoint>, maximumPoints: Int): List<HashratePoint> {
    if (points.size <= maximumPoints || maximumPoints < 3) return points
    val step = points.size.toDouble() / maximumPoints.toDouble()
    return buildList(maximumPoints) {
        var cursor = 0.0
        repeat(maximumPoints) {
            add(points[cursor.toInt().coerceIn(points.indices)])
            cursor += step
        }
        if (lastOrNull() != points.last()) {
            removeAt(lastIndex)
            add(points.last())
        }
    }
}

fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val remaining = safe % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remaining) else "%02d:%02d".format(minutes, remaining)
}

fun runtimePageSizeLabel(pageSize: Long): String = when (pageSize) {
    4_096L -> "4 KiB runtime"
    16_384L -> "16 KiB runtime"
    in 1L..Long.MAX_VALUE -> "$pageSize-byte runtime"
    else -> "Not reported"
}

fun thermalLabel(status: Int): String = when (status) {
    0 -> "Normal"
    1 -> "Light"
    2 -> "Moderate"
    3 -> "Severe"
    4 -> "Critical"
    5 -> "Emergency"
    6 -> "Shutdown"
    else -> "Unknown"
}

fun miningStateLabel(state: MiningState): String = when (state) {
    MiningState.STOPPED -> "Stopped"
    MiningState.STARTING -> "Starting"
    MiningState.CONNECTING -> "Connecting"
    MiningState.INITIALIZING -> "Preparing RandomX"
    MiningState.MINING_USER -> "Mining"
    MiningState.MINING_DONATION -> "Donation mining"
    MiningState.PAUSED -> "Paused"
    MiningState.STOPPING -> "Stopping"
    MiningState.ERROR -> "Error"
}

fun humanMiningSummary(state: MiningState, statusMessage: String): String = when (state) {
    MiningState.MINING_USER -> "Mining to user pool"
    MiningState.MINING_DONATION -> "Donation mining"
    MiningState.CONNECTING -> "Reconnecting to pool"
    MiningState.INITIALIZING -> if (statusMessage.contains("dataset", ignoreCase = true)) "Preparing RandomX dataset" else "Preparing RandomX"
    MiningState.PAUSED -> when {
        statusMessage.contains("thermal", ignoreCase = true) -> "Paused for temperature"
        statusMessage.contains("charger", ignoreCase = true) || statusMessage.contains("charging", ignoreCase = true) -> "Waiting for charger"
        statusMessage.contains("battery", ignoreCase = true) -> "Paused for battery"
        statusMessage.contains("network", ignoreCase = true) || statusMessage.contains("wifi", ignoreCase = true) -> "Paused for network policy"
        else -> "Mining paused"
    }
    MiningState.ERROR -> statusMessage.ifBlank { "Mining error" }
    else -> miningStateLabel(state)
}

fun thermalPressureFraction(status: Int): Float = status.coerceIn(0, 6) / 6f

fun shareLossFraction(accepted: Long, rejected: Long, stale: Long): Float {
    val lost = (rejected.coerceAtLeast(0L) + stale.coerceAtLeast(0L))
    val total = accepted.coerceAtLeast(0L) + lost
    return if (total == 0L) 0f else (lost.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}
