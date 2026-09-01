/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The banner's non-visual behaviour, which neither the goldens nor the accessibility-framework
 * checks can see: a live region is a behaviour, not a structural violation, so nothing else in
 * the suite would notice it disappearing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InlineBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun liveRegions() =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

    @Test
    fun `an error announces itself`() {
        compose.setContent {
            RatatoskrTheme { InlineBanner(kind = BannerKind.ERROR, text = "the load failed") }
        }

        liveRegions().assertCountEquals(1)
        liveRegions()[0].assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun `a notice does not announce itself`() {
        compose.setContent {
            RatatoskrTheme { InlineBanner(kind = BannerKind.NOTICE, text = "you were signed out") }
        }

        // A notice is already on screen when it opens, so it is reached in normal traversal order
        // and interrupting for it would be noise.
        liveRegions().assertCountEquals(0)
    }

    @Test
    fun `an action makes the whole banner tappable`() {
        var taps = 0
        compose.setContent {
            RatatoskrTheme {
                InlineBanner(
                    kind = BannerKind.ERROR,
                    text = "the shelf failed",
                    action = BannerAction(label = "tap to retry", onClick = { taps++ }),
                )
            }
        }

        compose.onNodeWithText("the shelf failed").assertHasClickAction().performClick()

        assert(taps == 1) { "expected the banner surface itself to be the retry target" }
    }
}
