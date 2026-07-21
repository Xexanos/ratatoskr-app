/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Previews / screenshot goldens for the settings screen (render in Android Studio without a
// running server), driving the public [SettingsScreen] off a fixed state (ADR 0001).

@Preview(name = "Settings", widthDp = 360, heightDp = 800)
@Composable
internal fun SettingsPreview() = RatatoskrTheme {
    Surface {
        SettingsScreen(
            state = SettingsUiState(serverUrl = "https://ratatoskr.home:8080"),
            onForgetCertificate = {},
            onSignOut = {},
            onClearImageCache = {},
        )
    }
}

@Preview(name = "Settings - not configured", widthDp = 360, heightDp = 800)
@Composable
internal fun SettingsUnconfiguredPreview() = RatatoskrTheme {
    Surface {
        SettingsScreen(
            state = SettingsUiState(serverUrl = null),
            onForgetCertificate = {},
            onSignOut = {},
            onClearImageCache = {},
        )
    }
}
