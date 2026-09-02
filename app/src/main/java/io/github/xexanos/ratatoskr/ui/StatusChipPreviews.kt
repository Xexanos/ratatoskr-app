/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Screenshot goldens for [StatusChip], kept in a sibling file so the production file stays the
// composable and nothing else. These pin the component directly rather than incidentally through
// the sign-in and now-playing screen goldens that happen to contain a chip.
//
// Both themes, because the ash ramp is pinned per theme (theme/Color.kt) and the accent dot's
// copper-on-ash pairing lands differently in each: light puts a dark copper on a pale leaf, dark
// puts a pale copper on a deep one.

@Composable
private fun ChipPreview(dark: Boolean, content: @Composable () -> Unit) {
    RatatoskrTheme(darkTheme = dark) {
        Surface { Box(Modifier.padding(16.dp)) { content() } }
    }
}

@Composable
private fun TrustChip() = StatusChip(
    label = stringResource(R.string.signin_server_trusted, "ratatoskr.home.arpa:8443"),
    leading = ChipLeading.Glyph(Icons.Outlined.Lock),
)

// The two dot emphases side by side: what this golden protects is that they stay tellable apart
// on the ash container, which is the pairing the tone decision in issue #156 introduced.
@Composable
private fun StateChips() = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    StatusChip(
        label = stringResource(R.string.nowplaying_state_playing),
        leading = ChipLeading.Dot(ChipDot.ACCENT),
    )
    StatusChip(
        label = stringResource(R.string.nowplaying_state_paused),
        leading = ChipLeading.Dot(ChipDot.MUTED),
    )
}

@Preview(name = "Chip - trust light", widthDp = 360)
@Composable
internal fun ChipTrustLightPreview() = ChipPreview(dark = false) { TrustChip() }

@Preview(name = "Chip - trust dark", widthDp = 360)
@Composable
internal fun ChipTrustDarkPreview() = ChipPreview(dark = true) { TrustChip() }

@Preview(name = "Chip - state dots light", widthDp = 360)
@Composable
internal fun ChipStateLightPreview() = ChipPreview(dark = false) { StateChips() }

@Preview(name = "Chip - state dots dark", widthDp = 360)
@Composable
internal fun ChipStateDarkPreview() = ChipPreview(dark = true) { StateChips() }
