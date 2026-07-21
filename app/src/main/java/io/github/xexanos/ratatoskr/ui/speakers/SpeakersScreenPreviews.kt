/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.speakers

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.ui.LocalImmediateLoading
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Previews / screenshot goldens for the speaker picker (render in Android Studio without a
// running server), driving the public [SpeakersScreen] off a fixed state (ADR 0001).

private val previewSpeakers = listOf(
    Speaker("lr", "Living Room", isGroup = false, members = emptyList()),
    Speaker("home", "Whole home", isGroup = true, members = listOf("Living Room", "Kitchen", "Study")),
)

@Preview(name = "Speakers - loaded", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersLoadedPreview() = RatatoskrTheme {
    Surface { SpeakersScreen(SpeakersUiState(loading = false, speakers = previewSpeakers)) {} }
}

@Preview(name = "Speakers - empty", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersEmptyPreview() = RatatoskrTheme {
    Surface { SpeakersScreen(SpeakersUiState(loading = false)) {} }
}

// Opens the 500 ms loading gate (see [LocalImmediateLoading]) so the loader is in the frame.
@Preview(name = "Speakers - loading", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersLoadingPreview() = RatatoskrTheme {
    CompositionLocalProvider(LocalImmediateLoading provides true) {
        Surface { SpeakersScreen(SpeakersUiState(loading = true)) {} }
    }
}
