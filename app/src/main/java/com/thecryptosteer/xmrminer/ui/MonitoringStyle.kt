package com.thecryptosteer.xmrminer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Flat, non-animated application backdrop. */
@Composable
fun MonitoringBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        content = content,
    )
}

/**
 * Adds the crisp panel outline and optional status-color top rule used throughout the app.
 * This deliberately contains no gradient, shimmer, blur, or sensor-driven behavior.
 */
fun Modifier.monitoringPanel(
    accent: Color = Color.Unspecified,
    topRuleHeightDp: Float = 2f,
): Modifier = composed {
    val outline = MaterialTheme.colorScheme.outlineVariant
    drawWithContent {
        drawContent()
        val strokeWidth = 1.dp.toPx()
        drawRect(
            color = outline,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(width = strokeWidth),
        )
        if (accent != Color.Unspecified) {
            drawRect(
                color = accent,
                topLeft = Offset.Zero,
                size = androidx.compose.ui.geometry.Size(size.width, topRuleHeightDp.dp.toPx()),
            )
        }
    }
}
