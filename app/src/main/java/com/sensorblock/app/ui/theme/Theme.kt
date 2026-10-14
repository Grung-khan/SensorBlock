package com.sensorblock.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Ink40,
    onPrimary = Color.White,
    primaryContainer = Ink90,
    onPrimaryContainer = Ink10,
    secondary = Steel40,
    onSecondary = Color.White,
    secondaryContainer = Steel90,
    onSecondaryContainer = Steel10,
    tertiary = Signal40,
    onTertiary = Color.White,
    tertiaryContainer = Signal90,
    onTertiaryContainer = Signal10,
    error = Alert40,
    onError = Color.White,
    errorContainer = Alert90,
    onErrorContainer = Alert10,
    background = SurfaceLight,
    onBackground = Ink10,
    surface = SurfaceLight,
    onSurface = Ink10,
    surfaceVariant = Steel90,
    onSurfaceVariant = Steel30,
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
)

private val DarkScheme = darkColorScheme(
    primary = Ink80,
    onPrimary = Ink10,
    primaryContainer = Ink30,
    onPrimaryContainer = Ink90,
    secondary = Steel80,
    onSecondary = Steel10,
    secondaryContainer = Steel30,
    onSecondaryContainer = Steel90,
    tertiary = Signal80,
    onTertiary = Signal10,
    tertiaryContainer = Signal30,
    onTertiaryContainer = Signal90,
    error = Alert80,
    onError = Alert10,
    errorContainer = Alert30,
    onErrorContainer = Alert90,
    background = SurfaceDark,
    onBackground = Ink90,
    surface = SurfaceDark,
    onSurface = Ink90,
    surfaceVariant = Steel30,
    onSurfaceVariant = Steel80,
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
)

/**
 * Semantic colours that Material 3 does not name, so screens can express
 * "protected" and "exposed" without hard-coding hues.
 */
data class SensorBlockColors(
    val protected: Color,
    val exposed: Color,
    val caution: Color,
    val onProtected: Color,
    val onExposed: Color,
    val onCaution: Color,
)

private val LightExtras = SensorBlockColors(
    protected = Signal40,
    exposed = Steel40,
    caution = Amber40,
    onProtected = Color.White,
    onExposed = Color.White,
    onCaution = Color.White,
)

private val DarkExtras = SensorBlockColors(
    protected = Signal80,
    exposed = Steel80,
    caution = Amber80,
    onProtected = Signal10,
    onExposed = Steel10,
    onCaution = Amber10,
)

val LocalSensorBlockColors = staticCompositionLocalOf { LightExtras }

@Composable
fun SensorBlockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val extras = if (darkTheme) DarkExtras else LightExtras

    CompositionLocalProvider(LocalSensorBlockColors provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SensorBlockTypography,
            content = content,
        )
    }
}

/** Shorthand used by screens: `SensorBlockColors.protected`. */
val sensorBlockColors: SensorBlockColors
    @Composable get() = LocalSensorBlockColors.current
