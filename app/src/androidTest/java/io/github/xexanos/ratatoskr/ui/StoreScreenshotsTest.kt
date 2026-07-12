/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xexanos.ratatoskr.MainActivity
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Captures the store screenshots for `fastlane/metadata/android/en-US/images/phoneScreenshots/`
 * (SPEC section 8) - not a test of anything; it reuses the [AppFlowTest] harness to drive the
 * real screens against a [HttpsMockServer] seeded with generic public-domain example content,
 * then shell-`screencap`s full-screen PNGs to `/data/local/tmp` (a location that survives the
 * app uninstall AGP performs after a connected run - the app's own external files dir does not).
 *
 * Skipped unless explicitly requested, so the regular instrumented runs (local and CI) never
 * pay for it. To (re)capture on a running emulator:
 *
 * ```
 * gradlew :app:connectedDebugAndroidTest \
 *   "-Pandroid.testInstrumentationRunnerArguments.class=io.github.xexanos.ratatoskr.ui.StoreScreenshotsTest" \
 *   "-Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true"
 * adb pull /data/local/tmp/store-screenshots/. \
 *   fastlane/metadata/android/en-US/images/phoneScreenshots/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {

    private val reset = ClearAppStateRule()
    private val server = HttpsMockServer()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(reset).around(server).around(compose)

    private fun str(id: Int): String = compose.activity.getString(id)

    // A single @Before: the assume must run before the server seeding, and JUnit 4 gives no
    // ordering guarantee between separate @Before methods.
    @Before
    fun assumeRequestedThenSeedServer() {
        assumeTrue(
            "Screenshot capture only runs when asked: pass the captureScreenshots runner argument.",
            InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true",
        )
        server.server.dispatcher = RatatoskrDispatcher(
            speakers = WireFixtures.speakerListJson(
                WireFixtures.speakerJson(id = "s1", name = "Living Room"),
                WireFixtures.speakerJson(id = "s2", name = "Kitchen"),
                WireFixtures.speakerJson(id = "s3", name = "Bedroom"),
                WireFixtures.speakerJson(
                    id = "s4",
                    name = "Downstairs",
                    isGroup = true,
                    members = listOf("Living Room", "Kitchen"),
                ),
            ),
            libraryPage = WireFixtures.libraryPageJson(
                items = listOf(
                    WireFixtures.libraryItemSummaryJson("b1", "Moby-Dick", "Herman Melville", 77_400.0),
                    WireFixtures.libraryItemSummaryJson("b2", "Pride and Prejudice", "Jane Austen", 41_400.0),
                    WireFixtures.libraryItemSummaryJson("b3", "The Count of Monte Cristo", "Alexandre Dumas", 190_800.0),
                    WireFixtures.libraryItemSummaryJson("b4", "Frankenstein", "Mary Shelley", 30_600.0),
                    WireFixtures.libraryItemSummaryJson("b5", "Treasure Island", "Robert Louis Stevenson", 27_000.0),
                    WireFixtures.libraryItemSummaryJson("b6", "The Odyssey", "Homer", 43_200.0),
                    WireFixtures.libraryItemSummaryJson("b7", "Jane Eyre", "Charlotte Brontë", 68_400.0),
                ),
            ),
            // The first library row (Moby-Dick) is what gets played; keep the session's
            // duration and embedded item consistent with its library listing (the
            // now-playing screen shows the embedded item's title).
            sessionDurationSeconds = 77_400.0,
            sessionItemJson = WireFixtures.libraryItemSummaryJson(
                "b1", "Moby-Dick", "Herman Melville", 77_400.0,
            ),
        )
    }

    @Test
    fun captureConnectLibraryAndNowPlaying() {
        // Connect screen, with a presentable example URL (never submitted - the real
        // MockWebServer URL replaces it right after the shot).
        compose.awaitText(str(R.string.connect_action_connect))
        compose.onNode(hasSetTextAction()).performTextReplacement("https://ratatoskr.example.com")
        capture("3-connect")

        compose.onNode(hasSetTextAction()).performTextReplacement(server.baseUrl)
        compose.onNodeWithText(str(R.string.connect_action_connect)).performClick()
        compose.awaitText(str(R.string.connect_action_trust))
        compose.onNodeWithText(str(R.string.connect_action_trust)).performClick()
        compose.awaitText(str(R.string.signin_action))
        compose.onNode(hasSetTextAction() and hasImeAction(ImeAction.Next)).performTextInput("alex")
        compose.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done)).performTextInput("secret")
        compose.onNodeWithText(str(R.string.signin_action)).performClick()

        compose.awaitTag(UiTestTags.LIBRARY_ROW)
        capture("1-library")

        compose.onAllNodesWithTag(UiTestTags.LIBRARY_ROW)[0].performClick()
        compose.awaitTag(UiTestTags.SPEAKER_ROW)
        compose.onAllNodesWithTag(UiTestTags.SPEAKER_ROW)[0].performClick()
        compose.awaitContentDescription(str(R.string.nowplaying_action_pause))
        capture("2-nowplaying")
    }

    /**
     * Full-screen PNG via shell `screencap` (system bars included, like a user's screenshot).
     * F-Droid orders screenshots by filename, hence the numeric prefixes.
     */
    private fun capture(name: String) {
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        // Let the IME dismissal and any trailing scrim animation finish; idleness alone does
        // not cover window animations outside Compose.
        SystemClock.sleep(400)
        shell("mkdir -p /data/local/tmp/store-screenshots")
        shell("screencap -p /data/local/tmp/store-screenshots/$name.png")
    }

    /** Runs a shell command via UiAutomation and drains its output (which awaits completion). */
    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }
}
