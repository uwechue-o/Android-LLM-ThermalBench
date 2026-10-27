package com.thermalbench.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Thermal severity is the one thing in this app that must read at a glance, so
 * the palette reserves a distinct colour for each throttle level rather than
 * leaving them to the dynamic scheme.
 */
object ThermalColors {
    val none = Color(0xFF2E7D32)
    val light = Color(0xFF9E9D24)
    val moderate = Color(0xFFF9A825)
    val severe = Color(0xFFEF6C00)
    val critical = Color(0xFFD84315)
    val emergency = Color(0xFFB71C1C)
    val shutdown = Color(0xFF4A148C)
    val unknown = Color(0xFF616161)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00629E),
    secondary = Color(0xFF51606F),
    tertiary = Color(0xFF68587A),
    error = Color(0xFFBA1A1A),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9BCBFB),
    secondary = Color(0xFFB9C8DA),
    tertiary = Color(0xFFD4BFE7),
    error = Color(0xFFFFB4AB),
)

@Composable
fun ThermalBenchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = ThermalBenchTypography, content = content)
}
