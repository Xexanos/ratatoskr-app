/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.library

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.Progress
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * That a failed load-more announces itself and stays a single tap target (issue #132). The
 * goldens see the banner but not its live region, and the accessibility-framework checks read a
 * live region as a behaviour rather than a structural violation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private val items = listOf(
        LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, Progress(12_600.0, false)),
        LibraryItemSummary("2", "Dune", "Frank Herbert", 75_600.0, null, null),
    )

    private fun library(state: LibraryUiState, onLoadMore: () -> Unit = {}) {
        compose.setContent {
            RatatoskrTheme {
                LibraryScreen(
                    state = state,
                    query = "",
                    onQueryChange = {},
                    onLoadMore = onLoadMore,
                    onOpenItem = {},
                    onOpenNowPlaying = {},
                    onOpenSettings = {},
                )
            }
        }
    }

    private fun liveRegions() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

    @Test
    fun `a failed load-more announces itself`() {
        library(LibraryUiState(items = items, nextCursor = "c2", loadMoreError = true))

        liveRegions().assertCountEquals(1)
        liveRegions()[0].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        // An actionable banner is one merged node, so the message is the announcing node's own.
        liveRegions()[0].assert(hasText(str(R.string.library_load_more_failed)))
    }

    @Test
    fun `the whole failed load-more retries the page`() {
        var retries = 0
        library(LibraryUiState(items = items, nextCursor = "c2", loadMoreError = true)) { retries++ }

        // The near-end effect asks for the page itself on composition (this short list is all
        // within the threshold), so what the tap has to add is one request on top of that.
        val beforeTap = retries
        liveRegions()[0].assertHasClickAction().performClick()

        assertEquals("expected the banner itself to be the retry target", beforeTap + 1, retries)
    }

    @Test
    fun `a page still loading announces nothing`() {
        library(LibraryUiState(items = items, nextCursor = "c2", loadingMore = true))

        liveRegions().assertCountEquals(0)
    }
}
