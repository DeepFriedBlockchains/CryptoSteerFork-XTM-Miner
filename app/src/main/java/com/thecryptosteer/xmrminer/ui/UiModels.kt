package com.thecryptosteer.xmrminer.ui

import androidx.compose.runtime.Immutable

enum class BenchmarkPhase { IDLE, PREPARING, RUNNING, CANCELLING, COMPLETE, FAILED }

@Immutable
data class BenchmarkUiState(
    val phase: BenchmarkPhase = BenchmarkPhase.IDLE,
    val elapsedSeconds: Int = 0,
    val targetSeconds: Int = 0,
    val completedHashes: Long = 0,
    val message: String = "",
    val resultJson: String = "",
    val error: String = "",
) {
    val running: Boolean
        get() = phase == BenchmarkPhase.PREPARING || phase == BenchmarkPhase.RUNNING || phase == BenchmarkPhase.CANCELLING
}

@Immutable
data class DiagnosticCheckState(
    val id: String,
    val title: String,
    val status: DiagnosticStatus = DiagnosticStatus.PENDING,
    val detail: String = "",
)

@Immutable
data class DiagnosticsUiState(
    val running: Boolean = false,
    val checks: List<DiagnosticCheckState> = defaultDiagnosticChecks(),
    val durationMillis: Long = 0L,
    val overallPassed: Boolean? = null,
    val reportJson: String = "",
)

fun defaultDiagnosticChecks(): List<DiagnosticCheckState> = listOf(
    DiagnosticCheckState("native", "Native library loading"),
    DiagnosticCheckState("json", "JSON boundary handling"),
    DiagnosticCheckState("hex", "Hexadecimal conversion"),
    DiagnosticCheckState("donation", "Donation ceiling logic"),
    DiagnosticCheckState("randomx", "Official RandomX test vector"),
    DiagnosticCheckState("jit", "JIT and interpreter agreement"),
    DiagnosticCheckState("pool", "Pool-message parser"),
    DiagnosticCheckState("tls", "TLS support"),
    DiagnosticCheckState("pagesize", "Runtime page-size compatibility"),
)

@Immutable
data class DonationTierUiState(
    val basisPoints: Int = 200,
    val activeUserNanos: Long = 0L,
    val remainingNanos: Long = 2_592_000_000_000_000L,
    val accountScoped: Boolean = false,
) {
    val percentLabel: String get() = "%.2f%%".format(java.util.Locale.US, basisPoints / 100.0)
}
