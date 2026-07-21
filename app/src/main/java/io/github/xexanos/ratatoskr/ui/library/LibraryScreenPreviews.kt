/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.library

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.Progress
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Previews / screenshot goldens for the library screen (render in Android Studio without a running
// server), kept in a sibling file so LibraryScreen.kt stays the screen. Each drives the internal
// LibraryContent off a fixed LibraryUiState - no ViewModel, no network - so Roborazzi goldens
// (build.gradle.kts: generateComposePreviewRobolectricTests) pin every state pixel-for-pixel.

private val previewItems = listOf(
    LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
    LibraryItemSummary("2", "Project Hail Mary", "Andy Weir", 57_600.0, null, Progress(57_600.0, true)),
    LibraryItemSummary("3", "Dune", "Frank Herbert", 75_600.0, null, null),
)

// Most-recently-listened first, as the server would deliver it. "The Hobbit" also sits in
// previewItems: the shelf is a view onto the library, not a partition, so the same book
// appearing in both sections is the state to preview.
private val previewShelfItems = listOf(
    LibraryItemSummary("4", "A Wizard of Earthsea", "Ursula K. Le Guin", 25_200.0, null, Progress(9_100.0, false)),
    LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
)

@Preview(name = "Library - shelf loaded", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryShelfLoadedPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, shelfItems = previewShelfItems),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

// A non-blank search field hides the shelf and both headers even though the held shelf items
// are still in state - the visibility rule lives here in previews (spec #80's testing
// decision), to be picked up by the screenshot goldens (#76).
@Preview(name = "Library - searching, shelf hidden", widthDp = 360, heightDp = 800)
@Composable
internal fun LibrarySearchingShelfHiddenPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems.take(1), shelfItems = previewShelfItems),
            query = "hobbit",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

// The shelf failed with nothing held while the list works: the slot keeps the tonal band and
// both section headers, with the tap-to-retry error row where the shelf's books would be.
// Contrast with LibraryLoadedPreview below - a successfully EMPTY shelf renders no section at
// all, so "failed" and "empty" can never look the same.
@Preview(name = "Library - shelf failed", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryShelfErrorPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, shelfError = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

// An empty shelf renders no section at all: this must look exactly like the screen before the
// shelf existed - no headers, no empty band.
@Preview(name = "Library - loaded, empty shelf", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadedPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - empty", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryEmptyPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = emptyList()),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - loading more", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadingMorePreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, nextCursor = "c2", loadingMore = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - load more failed", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadMoreFailedPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(items = previewItems, nextCursor = "c2", loadMoreError = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - loading", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryLoadingPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(loading = true),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "Library - error", widthDp = 360, heightDp = 800)
@Composable
internal fun LibraryErrorPreview() = RatatoskrTheme {
    Surface {
        LibraryContent(
            state = LibraryUiState(error = UiError.Domain(RatatoskrError.Upstream(code = null, message = "Audiobookshelf is unreachable."))),
            query = "",
            onQueryChange = {},
            onOpenItem = {},
            onOpenNowPlaying = {},
            onOpenSettings = {},
        )
    }
}
