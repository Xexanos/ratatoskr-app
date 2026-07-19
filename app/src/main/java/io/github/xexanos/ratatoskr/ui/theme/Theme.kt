/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = CopperPrimaryDark,
    onPrimary = CopperOnPrimaryDark,
    primaryContainer = CopperContainerDark,
    onPrimaryContainer = CopperOnContainerDark,
    secondary = AshSecondaryDark,
    secondaryContainer = AshContainerDark,
    onSecondaryContainer = AshOnContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = BackgroundDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
)

private val LightColorScheme = lightColorScheme(
    primary = CopperPrimaryLight,
    onPrimary = CopperOnPrimaryLight,
    primaryContainer = CopperContainerLight,
    onPrimaryContainer = CopperOnContainerLight,
    secondary = AshSecondaryLight,
    secondaryContainer = AshContainerLight,
    onSecondaryContainer = AshOnContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = BackgroundLight,
    onSurface = OnBackgroundLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
)

// The app's corner-radius scale - the single source of truth for shapes, so screens use
// MaterialTheme.shapes.* rather than literal RoundedCornerShape(n.dp). Values match the radii
// the screens were built with (12 / 16 / 20 dp).
private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun RatatoskrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the Ratatoskr brand palette shows consistently. Callers can opt into
    // Material You dynamic color (Android 12+) if desired.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            typography = Typography,
            content = content,
        )
    }
}
