/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.nowplaying

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.OffsetDateTime

/**
 * That a failed transport command announces itself (issue #132). A golden freezes the frame but
 * not the live region, and the accessibility-framework checks read a live region as a behaviour
 * rather than a structural violation - so nothing else in the suite notices it disappearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NowPlayingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private val session = Session(
        itemId = "1",
        item = LibraryItemSummary("1", "The Hobbit", "J. R. R. Tolkien", 39_600.0, null, null),
        speakerId = "living-room",
        state = PlaybackState.PAUSED,
        positionSeconds = 12_600.0,
        durationSeconds = 39_600.0,
        updatedAt = OffsetDateTime.parse("2026-07-04T12:00:00Z"),
    )

    private fun nowPlaying(error: UiError?) {
        compose.setContent {
            RatatoskrTheme {
                NowPlayingScreen(
                    state = NowPlayingUiState(loading = false, session = session, error = error),
                    onPause = {},
                    onResume = {},
                    onSeek = {},
                    onStop = {},
                )
            }
        }
    }

    private fun liveRegions() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

    @Test
    fun `a failed transport command announces itself`() {
        nowPlaying(error = UiError.Domain(RatatoskrError.Network(IOException())))

        liveRegions().assertCountEquals(1)
        liveRegions()[0].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        // The failure is what announces, not some other node that happens to carry a live region.
        // A banner with no action does not merge its children, so the message is a descendant.
        liveRegions()[0].assert(hasAnyDescendant(hasText(str(R.string.error_network))))
    }

    @Test
    fun `a session without a failure announces nothing`() {
        nowPlaying(error = null)

        liveRegions().assertCountEquals(0)
    }
}
