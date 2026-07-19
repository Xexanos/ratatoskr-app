/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has asked the system to reduce motion. Provided once at the theme root
 * (see [RatatoskrTheme]) so animated components - notably the knot loader - can fall back to
 * a static rendering without each one re-reading system settings.
 *
 * Defaults to `false` (animate) so previews and tests that don't wrap the theme still compose.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Android has no dedicated "reduce motion" toggle; the platform convention (and what the
 * accessibility "Remove animations" setting drives) is [Settings.Global.ANIMATOR_DURATION_SCALE]
 * being turned to 0. Pure so it can be unit-tested without a device.
 */
fun reducedMotionFromScale(scale: Float): Boolean = scale == 0f

/** Reads the current animator duration scale and maps it to a reduced-motion flag. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        reducedMotionFromScale(scale)
    }
}
