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
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.ui.LocalImmediateLoading
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import java.io.IOException

// Previews / screenshot goldens for the speaker picker (render in Android Studio without a
// running server), driving the public [SpeakersScreen] off a fixed state (ADR 0001).

private val previewSpeakers = listOf(
    Speaker("lr", "Living Room", isGroup = false, members = emptyList()),
    Speaker("home", "Whole home", isGroup = true, members = listOf("Living Room", "Kitchen", "Study")),
)

@Preview(name = "Speakers - loaded", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersLoadedPreview() = RatatoskrTheme {
    Surface {
        SpeakersScreen(
            state = SpeakersUiState(loading = false, speakers = previewSpeakers),
            onSelectSpeaker = {},
            onRetry = {},
        )
    }
}

@Preview(name = "Speakers - empty", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersEmptyPreview() = RatatoskrTheme {
    Surface {
        SpeakersScreen(state = SpeakersUiState(loading = false), onSelectSpeaker = {}, onRetry = {})
    }
}

// Opens the 500 ms loading gate (see [LocalImmediateLoading]) so the loader is in the frame.
@Preview(name = "Speakers - loading", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersLoadingPreview() = RatatoskrTheme {
    CompositionLocalProvider(LocalImmediateLoading provides true) {
        Surface {
            SpeakersScreen(state = SpeakersUiState(loading = true), onSelectSpeaker = {}, onRetry = {})
        }
    }
}

// The speaker list's failure state. Goldened because the screen reports it in the bare `error`
// role with no surface behind it, so a change to that role lands here unguarded otherwise - the
// glyph carries the role now that the state is a centred message with its own retry. A transport
// failure rather than a one-liner, so the golden also pins how the message wraps above the button.
@Preview(name = "Speakers - error", widthDp = 360, heightDp = 800)
@Composable
internal fun SpeakersErrorPreview() = RatatoskrTheme {
    Surface {
        SpeakersScreen(
            state = SpeakersUiState(loading = false, error = UiError.Domain(RatatoskrError.Network(IOException("unreachable")))),
            onSelectSpeaker = {},
            onRetry = {},
        )
    }
}
