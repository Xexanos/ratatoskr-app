/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.ui.auth.SignInErrorPreview
import io.github.xexanos.ratatoskr.ui.auth.SignInIdlePreview
import io.github.xexanos.ratatoskr.ui.connect.ConnectConfirmPreview
import io.github.xexanos.ratatoskr.ui.connect.ConnectErrorPreview
import io.github.xexanos.ratatoskr.ui.connect.ConnectIdlePreview
import io.github.xexanos.ratatoskr.ui.library.LibraryEmptyPreview
import io.github.xexanos.ratatoskr.ui.library.LibraryLoadedPreview
import io.github.xexanos.ratatoskr.ui.library.LibraryLoadingPreview
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingEmptyPreview
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingPausedPreview
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingPlayingPreview
import io.github.xexanos.ratatoskr.ui.settings.SettingsPreview
import io.github.xexanos.ratatoskr.ui.speakers.SpeakersEmptyPreview
import io.github.xexanos.ratatoskr.ui.speakers.SpeakersLoadedPreview
import io.github.xexanos.ratatoskr.ui.speakers.SpeakersLoadingPreview
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility conformance checks (SPEC section 9): runs the Accessibility Test
 * Framework — the same checks behind Accessibility Scanner — over every screen
 * preview. The checks cover missing/unspeakable labels, touch-target size, color
 * contrast and traversal order; a violation fails the test.
 *
 * These are instrumented tests on purpose: the checks walk the real accessibility
 * node tree, which only exists with a live accessibility system service, so they
 * silently pass on Robolectric (robolectric/robolectric#5642). The canary test
 * below fails loudly if that ever happens here.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // The default validator fails on ERROR-severity results. Raising it to WARNING
    // (AccessibilityValidator().setThrowExceptionFor(WARNING)) currently reports two
    // known construction sites and should become the default once they are fixed:
    // - Sign-in: the "Sign in" button's speakable text duplicates the heading.
    // - Now playing: the transport controls draw emoji glyphs ("▶"/"⏸"/"⏹")
    //   as text, whose fixed emoji colors defeat the text-contrast check (and TalkBack
    //   labeling) — they should become icons with contentDescriptions.
    private fun runChecks(content: @Composable () -> Unit) {
        compose.setContent(content)
        compose.enableAccessibilityChecks()
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun connectIdle() = runChecks { ConnectIdlePreview() }
    @Test fun connectConfirmCertificate() = runChecks { ConnectConfirmPreview() }
    @Test fun connectError() = runChecks { ConnectErrorPreview() }
    @Test fun signInIdle() = runChecks { SignInIdlePreview() }
    @Test fun signInError() = runChecks { SignInErrorPreview() }
    @Test fun libraryLoaded() = runChecks { LibraryLoadedPreview() }
    @Test fun libraryEmpty() = runChecks { LibraryEmptyPreview() }
    @Test fun libraryLoading() = runChecks { LibraryLoadingPreview() }
    @Test fun speakersLoaded() = runChecks { SpeakersLoadedPreview() }
    @Test fun speakersEmpty() = runChecks { SpeakersEmptyPreview() }
    @Test fun speakersLoading() = runChecks { SpeakersLoadingPreview() }
    @Test fun nowPlayingPlaying() = runChecks { NowPlayingPlayingPreview() }
    @Test fun nowPlayingPaused() = runChecks { NowPlayingPausedPreview() }
    @Test fun nowPlayingEmpty() = runChecks { NowPlayingEmptyPreview() }
    @Test fun settings() = runChecks { SettingsPreview() }

    /**
     * Canary: a knowingly inaccessible UI (unlabeled clickable, far below the 48dp
     * touch-target minimum) must make the checks throw. Guards against the whole
     * suite silently passing because the checks stopped seeing the node tree.
     */
    @Test
    fun checksActuallyDetectViolations() {
        compose.setContent {
            RatatoskrTheme {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .clickable {}
                        .semantics { contentDescription = "" },
                )
            }
        }
        compose.enableAccessibilityChecks()
        val violation = runCatching { compose.onRoot().tryPerformAccessibilityChecks() }
        assertTrue(
            "expected the accessibility checks to flag the canary UI, but they passed",
            violation.isFailure,
        )
    }
}
