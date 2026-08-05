package com.thecryptosteer.xmrminer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thecryptosteer.xmrminer.domain.HashratePoint
import com.thecryptosteer.xmrminer.domain.LogEntry
import com.thecryptosteer.xmrminer.domain.MiningState
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.roundToInt

enum class StatusTone { NEUTRAL, ACTION, LIVE, HEALTHY, WARNING, CRITICAL, PERFORMANCE }
enum class DiagnosticStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }
@Composable
private fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.ACTION -> MaterialTheme.colorScheme.primary
    StatusTone.LIVE -> CryptoSteerThemeTokens.colors.live
    StatusTone.HEALTHY -> CryptoSteerThemeTokens.colors.healthy
    StatusTone.WARNING -> CryptoSteerThemeTokens.colors.warning
    StatusTone.CRITICAL -> CryptoSteerThemeTokens.colors.critical
    StatusTone.PERFORMANCE -> CryptoSteerThemeTokens.colors.performance
}

@Composable
private fun toneContainer(tone: StatusTone): Color = toneColor(tone).copy(alpha = 0.13f)

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}


@Composable
fun MonitoringPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(17.dp).background(accent))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SemiCircleGauge(
    title: String,
    fraction: Float,
    displayValue: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color? = null,
) {
    val safeFraction = fraction.coerceIn(0f, 1f)
    val resolvedAccent = accent ?: gaugeToneColor(safeFraction)
    OutlinedCard(
        modifier = modifier.monitoringPanel(resolvedAccent),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonitoringPanelHeader(title = title, subtitle = subtitle, accent = resolvedAccent)
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1.75f),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val track = MaterialTheme.colorScheme.outlineVariant
                val healthy = CryptoSteerThemeTokens.colors.healthy
                val warning = CryptoSteerThemeTokens.colors.warning
                val critical = CryptoSteerThemeTokens.colors.critical
                val marker = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.75f)) {
                    val stroke = size.minDimension * 0.10f
                    val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, (size.height * 2f) - stroke)
                    val topLeft = Offset(stroke / 2f, stroke / 2f)
                    drawArc(track.copy(alpha = 0.46f), 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    drawArc(healthy.copy(alpha = 0.88f), 180f, 108f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    drawArc(warning.copy(alpha = 0.92f), 288f, 45f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    drawArc(critical.copy(alpha = 0.92f), 333f, 27f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))

                    val angle = Math.toRadians((180f + safeFraction * 180f).toDouble())
                    val radiusX = arcSize.width / 2f
                    val radiusY = arcSize.height / 2f
                    val center = Offset(topLeft.x + radiusX, topLeft.y + radiusY)
                    val inner = Offset(
                        x = center.x + cos(angle).toFloat() * (radiusX - stroke * 0.95f),
                        y = center.y + sin(angle).toFloat() * (radiusY - stroke * 0.95f),
                    )
                    val outer = Offset(
                        x = center.x + cos(angle).toFloat() * (radiusX + stroke * 0.02f),
                        y = center.y + sin(angle).toFloat() * (radiusY + stroke * 0.02f),
                    )
                    drawLine(marker, inner, outer, strokeWidth = 2.5f, cap = StrokeCap.Butt)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 2.dp)) {
                    Text(displayValue, style = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace))
                    Text("current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun gaugeToneColor(fraction: Float): Color = when {
    fraction >= 0.85f -> CryptoSteerThemeTokens.colors.critical
    fraction >= 0.60f -> CryptoSteerThemeTokens.colors.warning
    else -> CryptoSteerThemeTokens.colors.healthy
}

@Composable
fun StatusChip(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val color by animateColorAsState(toneColor(tone), label = "statusColor")
    Surface(
        modifier = modifier.semantics { contentDescription = label },
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
fun MiningStatusChip(state: MiningState, modifier: Modifier = Modifier) {
    val label = miningStateLabel(state)
    val tone = when (state) {
        MiningState.MINING_USER, MiningState.MINING_DONATION -> StatusTone.HEALTHY
        MiningState.CONNECTING, MiningState.INITIALIZING, MiningState.STARTING -> StatusTone.LIVE
        MiningState.PAUSED, MiningState.STOPPING -> StatusTone.WARNING
        MiningState.ERROR -> StatusTone.CRITICAL
        MiningState.STOPPED -> StatusTone.NEUTRAL
    }
    val icon = when (state) {
        MiningState.MINING_USER, MiningState.MINING_DONATION -> Icons.Outlined.Speed
        MiningState.CONNECTING, MiningState.INITIALIZING, MiningState.STARTING -> Icons.Outlined.Refresh
        MiningState.PAUSED -> Icons.Outlined.PauseCircle
        MiningState.STOPPING, MiningState.STOPPED -> Icons.Outlined.StopCircle
        MiningState.ERROR -> Icons.Outlined.ErrorOutline
    }
    StatusChip(label, tone, modifier, icon)
}

@Composable
fun ConnectionStatusChip(
    connected: Boolean,
    jobActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = when {
        connected && jobActive -> "Pool job active"
        connected -> "Connected"
        else -> "Disconnected"
    }
    val tone = when {
        connected && jobActive -> StatusTone.LIVE
        connected -> StatusTone.HEALTHY
        else -> StatusTone.NEUTRAL
    }
    StatusChip(label, tone, modifier, if (connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff)
}

@Composable
fun ThermalStatusIndicator(status: Int, modifier: Modifier = Modifier, compact: Boolean = true) {
    val tone = when {
        status >= 5 -> StatusTone.CRITICAL
        status >= 3 -> StatusTone.WARNING
        status >= 1 -> StatusTone.LIVE
        else -> StatusTone.HEALTHY
    }
    val label = if (compact) thermalLabel(status) else "Thermal ${thermalLabel(status)}"
    StatusChip(label, tone, modifier, Icons.Outlined.Thermostat)
}

@Composable
fun LiveDataChip(updatedAtMillis: Long, modifier: Modifier = Modifier) {
    val age = (System.currentTimeMillis() - updatedAtMillis).coerceAtLeast(0L)
    val live = updatedAtMillis > 0L && age < 2_000L
    StatusChip(if (live) "Live" else "${max(1L, age / 1_000L)}s old", if (live) StatusTone.LIVE else StatusTone.WARNING, modifier)
}

@Composable
fun LiveMetricText(
    value: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = 72.dp,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.End,
) {
    Text(
        text = value,
        modifier = modifier.widthIn(min = minWidth),
        style = style.copy(fontFamily = FontFamily.Monospace),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String = "",
    icon: ImageVector? = null,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    val accent = toneColor(tone)
    OutlinedCard(
        modifier = modifier.heightIn(min = 92.dp).monitoringPanel(accent),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (icon != null) Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
            }
            LiveMetricText(value, minWidth = 0.dp, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Start)
            if (supporting.isNotBlank()) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CompactMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: StatusTone = StatusTone.NEUTRAL,
    supporting: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(3.dp)).background(toneContainer(tone)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = toneColor(tone), modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!supporting.isNullOrBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LiveMetricText(value, minWidth = 82.dp, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DefinitionRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, color = if (tone == StatusTone.NEUTRAL) MaterialTheme.colorScheme.onSurface else toneColor(tone), textAlign = TextAlign.End)
    }
}

@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(3.dp),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun SecondaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(3.dp),
    ) {
        if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
        if (icon != null) Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun DestructiveActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
    ) {
        if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
        if (icon != null) Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Surface(
                modifier = Modifier
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(role = Role.RadioButton) { onSelected(option) },
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.68f) else MaterialTheme.colorScheme.outlineVariant,
                ),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(label(option), Modifier.padding(horizontal = 14.dp, vertical = 11.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    minimumLabel: String = range.start.toString(),
    maximumLabel: String = range.endInclusive.toString(),
    supportingText: String? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var previousStep by remember(value, range, steps) { mutableIntStateOf(-1) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(3.dp)) {
                LiveMetricText(valueLabel, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), minWidth = 48.dp, style = MaterialTheme.typography.labelLarge)
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { newValue ->
                val denominator = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
                val discrete = if (steps > 0) (((newValue - range.start) / denominator) * (steps + 1)).roundToInt() else newValue.roundToInt()
                if (discrete != previousStep) {
                    previousStep = discrete
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onValueChange(newValue)
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(minimumLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(maximumLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!supportingText.isNullOrBlank()) Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!supportingText.isNullOrBlank()) Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun ExpandableTechnicalDetails(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    OutlinedCard(
        modifier = modifier.fillMaxWidth().monitoringPanel(MaterialTheme.colorScheme.primary),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand")
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
            }
        }
    }
}

@Composable
fun InlineBanner(
    title: String,
    message: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val color = toneColor(tone)
    val icon = when (tone) {
        StatusTone.CRITICAL -> Icons.Outlined.ErrorOutline
        StatusTone.WARNING -> Icons.Outlined.WarningAmber
        StatusTone.HEALTHY -> Icons.Outlined.CheckCircle
        StatusTone.LIVE -> Icons.Outlined.Info
        else -> Icons.Outlined.HelpOutline
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.11f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(3.dp),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = color, modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = color)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!actionLabel.isNullOrBlank() && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun InlineWarningBanner(title: String, message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    InlineBanner(title, message, StatusTone.WARNING, modifier, actionLabel, onAction)
}

@Composable
fun SuccessBanner(title: String, message: String, modifier: Modifier = Modifier) {
    InlineBanner(title, message, StatusTone.HEALTHY, modifier)
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(38.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!actionLabel.isNullOrBlank() && onAction != null) PrimaryActionButton(actionLabel, onAction)
    }
}

@Composable
fun LoadingState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    EmptyState(title, message, modifier, Icons.Outlined.ErrorOutline, actionLabel, onAction)
}

@Composable
fun ProgressPanel(
    title: String,
    message: String,
    progress: Float?,
    modifier: Modifier = Modifier,
    elapsed: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().monitoringPanel(CryptoSteerThemeTokens.colors.performance),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, CryptoSteerThemeTokens.colors.performance.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (!elapsed.isNullOrBlank()) Text(elapsed, style = MaterialTheme.typography.labelMedium, color = CryptoSteerThemeTokens.colors.performance)
                }
                StatusChip("Running", StatusTone.PERFORMANCE, icon = Icons.Outlined.Schedule)
            }
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().progressSemantics(progress.coerceIn(0f, 1f)))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onCancel != null) DestructiveActionButton("Cancel safely", onCancel, icon = Icons.Outlined.Cancel)
        }
    }
}

@Composable
fun PerThreadPerformanceBar(
    worker: Int,
    cpu: Int?,
    currentHashrate: Double,
    fastestHashrate: Double,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    val ratio = if (fastestHashrate > 0.0) (currentHashrate / fastestHashrate).coerceIn(0.0, 1.0).toFloat() else 0f
    val animated by animateFloatAsState(ratio, label = "workerProgress")
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Worker ${worker + 1}", style = MaterialTheme.typography.titleSmall)
                Text("CPU ${cpu?.takeIf { it >= 0 }?.toString() ?: "auto"}${if (paused) " • paused" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LiveMetricText("%.2f H/s".format(currentHashrate), minWidth = 104.dp, style = MaterialTheme.typography.bodyLarge)
        }
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))) {
            Box(Modifier.fillMaxWidth(animated).height(7.dp).background(if (paused) CryptoSteerThemeTokens.colors.warning else MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
fun LogEntryRow(
    entry: LogEntry,
    repeatedCount: Int = 1,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember(entry.timeMillis, entry.message) { mutableStateOf(initiallyExpanded) }
    val tone = when (entry.level.uppercase()) {
        "ERROR", "CRITICAL" -> StatusTone.CRITICAL
        "WARN", "WARNING" -> StatusTone.WARNING
        "SUCCESS" -> StatusTone.HEALTHY
        else -> StatusTone.NEUTRAL
    }
    val icon = when (tone) {
        StatusTone.CRITICAL -> Icons.Outlined.ErrorOutline
        StatusTone.WARNING -> Icons.Outlined.WarningAmber
        StatusTone.HEALTHY -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Info
    }
    Column(
        modifier = modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = toneColor(tone), modifier = Modifier.size(19.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        entry.message.lineSequence().firstOrNull().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (repeatedCount > 1) StatusChip("× $repeatedCount", StatusTone.NEUTRAL)
                }
                Text(
                    "${entry.level.uppercase()} • ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry.timeMillis))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse log" else "Expand log", modifier = Modifier.size(20.dp))
        }
        AnimatedVisibility(expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 29.dp),
            ) {
                Text(
                    entry.message,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
fun DiagnosticResultRow(
    title: String,
    status: DiagnosticStatus,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val tone = when (status) {
        DiagnosticStatus.PASSED -> StatusTone.HEALTHY
        DiagnosticStatus.FAILED -> StatusTone.CRITICAL
        DiagnosticStatus.RUNNING -> StatusTone.LIVE
        DiagnosticStatus.SKIPPED -> StatusTone.WARNING
        DiagnosticStatus.PENDING -> StatusTone.NEUTRAL
    }
    val icon = when (status) {
        DiagnosticStatus.PASSED -> Icons.Outlined.CheckCircle
        DiagnosticStatus.FAILED -> Icons.Outlined.Cancel
        DiagnosticStatus.RUNNING -> Icons.Outlined.Refresh
        DiagnosticStatus.SKIPPED -> Icons.Outlined.WarningAmber
        DiagnosticStatus.PENDING -> Icons.Outlined.Schedule
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = toneColor(tone), modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(status.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium, color = toneColor(tone))
    }
}

@Composable
fun ProfileSelectionCard(
    name: String,
    description: String,
    selected: Boolean,
    performance: String,
    heat: String,
    battery: String,
    threads: String,
    icon: ImageVector,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outline by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "profileOutline")
    OutlinedCard(
        modifier = modifier.fillMaxWidth().clickable(enabled = !selected, onClick = onSelect).monitoringPanel(if (selected) MaterialTheme.colorScheme.primary else CryptoSteerThemeTokens.colors.live),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) CryptoSteerThemeTokens.colors.selected else MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = CryptoSteerThemeTokens.colors.healthy)
                    }
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Performance $performance", StatusTone.PERFORMANCE)
                StatusChip("Heat $heat", if (heat.equals("High", true) || heat.equals("Maximum", true)) StatusTone.WARNING else StatusTone.NEUTRAL)
                StatusChip("Battery $battery", StatusTone.NEUTRAL)
                StatusChip(threads, StatusTone.LIVE)
            }
            if (!selected) OutlinedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text("Apply profile") }
        }
    }
}

@Composable
fun BenchmarkResultCard(
    title: String,
    result: String,
    details: List<Pair<String, String>>,
    best: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth().monitoringPanel(if (best) CryptoSteerThemeTokens.colors.performance else MaterialTheme.colorScheme.primary),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (best) CryptoSteerThemeTokens.colors.performance.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    LiveMetricText(result, minWidth = 0.dp, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Start, color = if (best) CryptoSteerThemeTokens.colors.performance else MaterialTheme.colorScheme.onSurface)
                }
                if (best) StatusChip("Best", StatusTone.PERFORMANCE, icon = Icons.Outlined.Speed)
            }
            details.forEach { (label, value) -> DefinitionRow(label, value) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) { Text("Copy") }
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
        }
    }
}

@Composable
fun LiveHashrateChart(
    points: List<HashratePoint>,
    modifier: Modifier = Modifier,
    selectedRange: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
) {
    val now = System.currentTimeMillis()
    val window = remember(points, selectedRange, now / 1_000L) {
        chartWindow(points, selectedRange, now)
    }
    val filtered = remember(window.points) { decimatePoints(window.points, 260) }
    val current = window.points.lastOrNull()?.hashrate ?: 0.0
    val average = if (window.points.isEmpty()) 0.0 else window.points.sumOf { it.hashrate } / window.points.size
    val minimum = window.points.minOfOrNull { it.hashrate } ?: 0.0
    val maximum = window.points.maxOfOrNull { it.hashrate } ?: 0.0
    var selectedIndex by remember(filtered, selectedRange) { mutableIntStateOf(-1) }
    val chartLineColor = CryptoSteerThemeTokens.colors.healthy
    val chartAverageColor = CryptoSteerThemeTokens.colors.warning
    val chartThermalColor = CryptoSteerThemeTokens.colors.critical

    Card(
        modifier = modifier.fillMaxWidth().monitoringPanel(chartLineColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MonitoringPanelHeader(
                title = "Hashrate",
                subtitle = "${selectedRange.label} window • ${window.points.size} samples",
                accent = chartLineColor,
                trailing = {
                    LiveMetricText("%.1f H/s".format(current), minWidth = 104.dp, style = MaterialTheme.typography.titleLarge)
                },
            )
            SegmentedSelector(ChartRange.entries, selectedRange, { it.label }, onSelected = onRangeSelected)
            if (filtered.size < 2) {
                Box(
                    Modifier.fillMaxWidth().height(190.dp).background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (points.isEmpty()) "Hashrate history will appear after mining starts." else "Not enough samples in the ${selectedRange.label.lowercase()} window yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val chartAxisColor = MaterialTheme.colorScheme.outlineVariant
                val chartBackground = MaterialTheme.colorScheme.background
                val chartPointFillColor = MaterialTheme.colorScheme.surface
                val selectionColor = MaterialTheme.colorScheme.primary
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(chartBackground)
                        .pointerInput(filtered, window.startMillis, window.endMillis) {
                            detectTapGestures { offset ->
                                val selectedTime = window.startMillis + ((offset.x / size.width).coerceIn(0f, 1f) * window.durationMillis).toLong()
                                selectedIndex = filtered.indices.minByOrNull { index -> kotlin.math.abs(filtered[index].timeMillis - selectedTime) } ?: -1
                            }
                        }
                        .semantics {
                            contentDescription = "Hashrate chart for ${selectedRange.label}. Current %.1f, average %.1f, minimum %.1f, maximum %.1f hashes per second.".format(current, average, minimum, maximum)
                        },
                ) {
                    val topPad = 12f
                    val bottomPad = 18f
                    val availableHeight = size.height - topPad - bottomPad
                    val maxRate = max(1.0, maximum * 1.08)
                    fun pointX(point: HashratePoint): Float = (((point.timeMillis - window.startMillis).toDouble() / window.durationMillis.toDouble()).coerceIn(0.0, 1.0) * size.width).toFloat()

                    repeat(5) { index ->
                        val y = topPad + availableHeight * index / 4f
                        drawLine(chartAxisColor.copy(alpha = 0.62f), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    repeat(7) { index ->
                        val x = size.width * index / 6f
                        drawLine(chartAxisColor.copy(alpha = 0.48f), Offset(x, topPad), Offset(x, size.height - bottomPad), 1f)
                    }

                    val averageY = topPad + availableHeight - (average / maxRate * availableHeight).toFloat()
                    drawLine(
                        chartAverageColor.copy(alpha = 0.85f),
                        Offset(0f, averageY),
                        Offset(size.width, averageY),
                        1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 6f)),
                    )

                    val linePath = Path()
                    val areaPath = Path()
                    filtered.forEachIndexed { index, point ->
                        val x = pointX(point)
                        val y = topPad + availableHeight - (point.hashrate / maxRate * availableHeight).toFloat()
                        if (index == 0) {
                            linePath.moveTo(x, y)
                            areaPath.moveTo(x, size.height - bottomPad)
                            areaPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            areaPath.lineTo(x, y)
                        }
                        if (point.thermalStatus >= 3) drawCircle(chartThermalColor, 3f, Offset(x, y))
                    }
                    areaPath.lineTo(pointX(filtered.last()), size.height - bottomPad)
                    areaPath.close()
                    drawPath(areaPath, color = chartLineColor.copy(alpha = 0.10f))
                    drawPath(linePath, color = chartLineColor, style = Stroke(width = 2.3f, cap = StrokeCap.Butt))
                    drawLine(chartAxisColor, Offset(0f, size.height - bottomPad), Offset(size.width, size.height - bottomPad), 1f)

                    if (selectedIndex in filtered.indices) {
                        val point = filtered[selectedIndex]
                        val x = pointX(point)
                        val y = topPad + availableHeight - (point.hashrate / maxRate * availableHeight).toFloat()
                        drawLine(selectionColor.copy(alpha = 0.78f), Offset(x, 0f), Offset(x, size.height), 1.5f)
                        drawCircle(chartPointFillColor, 6f, Offset(x, y))
                        drawCircle(chartLineColor, 4f, Offset(x, y))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(window.startMillis)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(window.endMillis)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selectedIndex in filtered.indices) {
                    val point = filtered[selectedIndex]
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
                        Text(
                            "${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(point.timeMillis))} • %.1f H/s • thermal ${thermalLabel(point.thermalStatus)} • battery ${point.batteryPercent}%".format(point.hashrate),
                            Modifier.padding(9.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ChartSummary("Average", average, chartAverageColor)
                ChartSummary("Minimum", minimum, MaterialTheme.colorScheme.onSurfaceVariant)
                ChartSummary("Maximum", maximum, chartLineColor)
            }
        }
    }
}

@Composable
private fun ChartSummary(label: String, value: Double, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, MaterialTheme.shapes.extraSmall))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LiveMetricText("%.1f".format(value), minWidth = 48.dp, style = MaterialTheme.typography.labelMedium)
    }
}
