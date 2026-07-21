/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.nowplaying

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.ui.LocalImmediateLoading
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import java.time.OffsetDateTime

// Previews / screenshot goldens for the now-playing screen (render in Android Studio without a
// running server), driving the public [NowPlayingScreen] off a fixed state (ADR 0001). Before the
// Screen/ScreenHost split these previews rendered a preview-side copy of the screen's layout
// (the header + branching lived in the stateful composable and was unreachable); now they render
// the real structure, so layout drift fails the goldens.

private fun previewSession(state: PlaybackState) = Session(
    itemId = "1",
    item = LibraryItemSummary(
        id = "1",
        title = "The Hobbit",
        author = "J. R. R. Tolkien",
        durationSeconds = 39_600.0,
        coverUrl = null,
        progress = null,
    ),
    speakerId = "living-room",
    state = state,
    positionSeconds = 12_600.0,
    durationSeconds = 39_600.0,
    updatedAt = OffsetDateTime.parse("2026-07-04T12:00:00Z"),
)

@Composable
private fun NowPlayingPreview(state: NowPlayingUiState) = RatatoskrTheme {
    Surface {
        NowPlayingScreen(state = state, onPause = {}, onResume = {}, onSeek = {}, onStop = {})
    }
}

@Preview(name = "Now playing - playing", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingPlayingPreview() =
    NowPlayingPreview(NowPlayingUiState(loading = false, session = previewSession(PlaybackState.PLAYING)))

@Preview(name = "Now playing - paused", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingPausedPreview() =
    NowPlayingPreview(NowPlayingUiState(loading = false, session = previewSession(PlaybackState.PAUSED)))

@Preview(name = "Now playing - nothing playing", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingEmptyPreview() =
    NowPlayingPreview(NowPlayingUiState(loading = false, session = null))

// Opens the 500 ms loading gate (see [LocalImmediateLoading]) so the loader is in the frame - a
// state the pre-split previews could not reach at all.
@Preview(name = "Now playing - loading", widthDp = 360, heightDp = 800)
@Composable
internal fun NowPlayingLoadingPreview() = RatatoskrTheme {
    CompositionLocalProvider(LocalImmediateLoading provides true) {
        Surface {
            NowPlayingScreen(
                state = NowPlayingUiState(loading = true),
                onPause = {},
                onResume = {},
                onSeek = {},
                onStop = {},
            )
        }
    }
}
