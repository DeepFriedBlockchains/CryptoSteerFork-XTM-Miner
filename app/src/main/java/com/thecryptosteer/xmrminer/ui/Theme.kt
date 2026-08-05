package com.thecryptosteer.xmrminer.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Monitoring-console palette inspired by professional infrastructure dashboards.
 * Color is reserved for state and data, while surfaces remain neutral and high contrast.
 */
object CryptoSteerColors {
    val AppBackgroundDark = Color(0xFF0B0C0E)
    val NavigationDark = Color(0xFF111217)
    val SurfaceDark = Color(0xFF181B1F)
    val SurfaceElevatedDark = Color(0xFF1F2329)
    val SurfaceSelectedDark = Color(0xFF272B32)
    val OutlineDark = Color(0xFF2D323A)
    val OutlineStrongDark = Color(0xFF3C434D)

    val BrandOrange = Color(0xFFFF9830)
    val BrandOrangePressed = Color(0xFFE57C17)
    val SignalBlue = Color(0xFF5794F2)
    val SignalCyan = Color(0xFF6ED0E0)
    val HealthyGreen = Color(0xFF73BF69)
    val WarningAmber = Color(0xFFFFB357)
    val CriticalRed = Color(0xFFF2495C)
    val AlertYellow = Color(0xFFFADE2A)

    val TextPrimaryDark = Color(0xFFD8D9DA)
    val TextSecondaryDark = Color(0xFFA7B0BE)
    val TextMutedDark = Color(0xFF6E7681)
    val DisabledDark = Color(0xFF4D545D)

    val AppBackgroundLight = Color(0xFFF4F5F7)
    val NavigationLight = Color(0xFFFFFFFF)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceElevatedLight = Color(0xFFE9ECF1)
    val SurfaceSelectedLight = Color(0xFFDDE5F1)
    val OutlineLight = Color(0xFFD0D5DD)
    val OutlineStrongLight = Color(0xFF98A2B3)
    val TextPrimaryLight = Color(0xFF1D2939)
    val TextSecondaryLight = Color(0xFF475467)
    val TextMutedLight = Color(0xFF667085)
}

private val DarkColors = darkColorScheme(
    primary = CryptoSteerColors.BrandOrange,
    onPrimary = Color(0xFF241204),
    primaryContainer = Color(0xFF4A2B12),
    onPrimaryContainer = Color(0xFFFFD9B5),
    secondary = CryptoSteerColors.SignalBlue,
    onSecondary = Color(0xFF07192F),
    secondaryContainer = Color(0xFF17365D),
    onSecondaryContainer = Color(0xFFD9E8FF),
    tertiary = CryptoSteerColors.HealthyGreen,
    onTertiary = Color(0xFF0D220D),
    tertiaryContainer = Color(0xFF244825),
    onTertiaryContainer = Color(0xFFD8F4D3),
    error = CryptoSteerColors.CriticalRed,
    onError = Color(0xFF30070D),
    errorContainer = Color(0xFF55212B),
    onErrorContainer = Color(0xFFFFD9DF),
    background = CryptoSteerColors.AppBackgroundDark,
    onBackground = CryptoSteerColors.TextPrimaryDark,
    surface = CryptoSteerColors.SurfaceDark,
    onSurface = CryptoSteerColors.TextPrimaryDark,
    surfaceVariant = CryptoSteerColors.SurfaceElevatedDark,
    onSurfaceVariant = CryptoSteerColors.TextSecondaryDark,
    outline = CryptoSteerColors.OutlineStrongDark,
    outlineVariant = CryptoSteerColors.OutlineDark,
    inverseSurface = CryptoSteerColors.TextPrimaryDark,
    inverseOnSurface = CryptoSteerColors.AppBackgroundDark,
    inversePrimary = Color(0xFF9D4B08),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB75200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF3A1800),
    secondary = Color(0xFF3268B0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E6FF),
    onSecondaryContainer = Color(0xFF092A52),
    tertiary = Color(0xFF397A36),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5F0CF),
    onTertiaryContainer = Color(0xFF0D2A0D),
    error = Color(0xFFB42332),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF410008),
    background = CryptoSteerColors.AppBackgroundLight,
    onBackground = CryptoSteerColors.TextPrimaryLight,
    surface = CryptoSteerColors.SurfaceLight,
    onSurface = CryptoSteerColors.TextPrimaryLight,
    surfaceVariant = CryptoSteerColors.SurfaceElevatedLight,
    onSurfaceVariant = CryptoSteerColors.TextSecondaryLight,
    outline = CryptoSteerColors.OutlineStrongLight,
    outlineVariant = CryptoSteerColors.OutlineLight,
    inverseSurface = Color(0xFF20242A),
    inverseOnSurface = Color(0xFFF4F5F7),
    inversePrimary = Color(0xFFFFB36C),
    scrim = Color.Black,
)

private val CryptoTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.4).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.25.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.35.sp),
)

private val CryptoShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(5.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

@Immutable
data class CryptoSteerSemanticColors(
    val live: Color,
    val healthy: Color,
    val warning: Color,
    val critical: Color,
    val performance: Color,
    val muted: Color,
    val navigation: Color,
    val elevated: Color,
    val selected: Color,
    val outlineSubtle: Color,
)

val LocalCryptoSteerSemanticColors = staticCompositionLocalOf {
    CryptoSteerSemanticColors(
        live = CryptoSteerColors.SignalBlue,
        healthy = CryptoSteerColors.HealthyGreen,
        warning = CryptoSteerColors.WarningAmber,
        critical = CryptoSteerColors.CriticalRed,
        performance = CryptoSteerColors.SignalCyan,
        muted = CryptoSteerColors.TextMutedDark,
        navigation = CryptoSteerColors.NavigationDark,
        elevated = CryptoSteerColors.SurfaceElevatedDark,
        selected = CryptoSteerColors.SurfaceSelectedDark,
        outlineSubtle = CryptoSteerColors.OutlineDark,
    )
}

object CryptoSteerThemeTokens {
    val colors: CryptoSteerSemanticColors
        @Composable get() = LocalCryptoSteerSemanticColors.current
}

@Composable
fun CryptoSteerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) {
        CryptoSteerSemanticColors(
            live = CryptoSteerColors.SignalBlue,
            healthy = CryptoSteerColors.HealthyGreen,
            warning = CryptoSteerColors.WarningAmber,
            critical = CryptoSteerColors.CriticalRed,
            performance = CryptoSteerColors.SignalCyan,
            muted = CryptoSteerColors.TextMutedDark,
            navigation = CryptoSteerColors.NavigationDark,
            elevated = CryptoSteerColors.SurfaceElevatedDark,
            selected = CryptoSteerColors.SurfaceSelectedDark,
            outlineSubtle = CryptoSteerColors.OutlineDark,
        )
    } else {
        CryptoSteerSemanticColors(
            live = Color(0xFF3268B0),
            healthy = Color(0xFF397A36),
            warning = Color(0xFFB15C00),
            critical = Color(0xFFB42332),
            performance = Color(0xFF257A8A),
            muted = CryptoSteerColors.TextMutedLight,
            navigation = CryptoSteerColors.NavigationLight,
            elevated = CryptoSteerColors.SurfaceElevatedLight,
            selected = CryptoSteerColors.SurfaceSelectedLight,
            outlineSubtle = CryptoSteerColors.OutlineLight,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        SideEffect {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalCryptoSteerSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = CryptoTypography,
            shapes = CryptoShapes,
            content = content,
        )
    }
}
