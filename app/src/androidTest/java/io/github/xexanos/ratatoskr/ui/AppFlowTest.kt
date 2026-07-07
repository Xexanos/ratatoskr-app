/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.MainActivity
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.RatatoskrApp
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * SPEC section 9, layer 3 - the whole app driven through its UI (the Playwright analogue).
 * Each test launches the real [MainActivity] and taps/types through the actual screens; the
 * app itself drives its real navigation -> ViewModels -> ConnectionManager -> core-network
 * against a [HttpsMockServer] standing in for the Ratatoskr server.
 *
 * This layer asserts user-visible outcomes and flow; the wire-level mechanics (token rotation,
 * error taxonomy, enum fallback, concurrency) are the component layer's job (see
 * core-network `Factory*ComponentTest`) and are deliberately not re-checked here.
 *
 * Synchronisation: the clock is left auto-advancing (default). Material3 spinners animate
 * forever, but here they are transient - MockWebServer answers in milliseconds, so each load
 * state clears within a frame or two and idle is reached. [awaitTag]/[awaitText]/
 * [awaitContentDescription] poll for the post-load nodes across those brief spinners. Freezing
 * the clock (`autoAdvance = false`) is deliberately NOT used: it also freezes recomposition, so
 * the awaited screens would never render. A minimal launch smoke test
 * ([appLaunchesToTheConnectScreen]) fails fast and unambiguously if the sync approach is wrong,
 * before the multi-step flows.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {

    private val reset = ClearAppStateRule()
    private val server = HttpsMockServer()
    private val compose = createAndroidComposeRule<MainActivity>()

    // reset (clear persisted state) -> start MockWebServer -> launch MainActivity.
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(reset).around(server).around(compose)

    private fun str(id: Int): String = compose.activity.getString(id)

    // Every test starts with the happy-path server; a test needing different behaviour
    // overrides it via useDispatcher(...). Runs after the rule chain has started the server.
    @Before
    fun installDefaultServer() {
        server.server.dispatcher = RatatoskrDispatcher()
    }

    private fun useDispatcher(dispatcher: RatatoskrDispatcher) {
        server.server.dispatcher = dispatcher
    }

    /** Connect (type URL, confirm the shown certificate) then submit sign-in. */
    private fun connectTrustAndSubmitSignIn() {
        compose.awaitText(str(R.string.connect_action_connect))
        compose.onNode(hasSetTextAction()).performTextReplacement(server.baseUrl)
        compose.onNodeWithText(str(R.string.connect_action_connect)).performClick()

        // Wait for the confirm state (its Trust button); awaitText dumps the screen on timeout.
        compose.awaitText(str(R.string.connect_action_trust))
        // The confirm card shows the served leaf's fingerprint; asserting it matches the
        // fixture's is the "confirm the certificate we were shown" check.
        compose.onNodeWithText(server.fingerprint).assertExists()
        compose.onNodeWithText(str(R.string.connect_action_trust)).performClick()

        compose.awaitText(str(R.string.signin_action))
        compose.onNode(hasSetTextAction() and hasImeAction(ImeAction.Next)).performTextInput("alex")
        compose.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done)).performTextInput("secret")
        compose.onNodeWithText(str(R.string.signin_action)).performClick()
    }

    @Test
    fun appLaunchesToTheConnectScreen() {
        // Guards the whole sync approach: the app must render past its startup spinner to the
        // connect screen. If the wait strategy is wrong this fails here, before any flow.
        compose.awaitText(str(R.string.connect_action_connect))
        compose.onNodeWithText(str(R.string.connect_action_connect)).assertIsDisplayed()
    }

    @Test
    fun connectSignInLibrarySpeakersNowPlaying() {
        connectTrustAndSubmitSignIn()

        // Library -> open the first book.
        compose.awaitTag(UiTestTags.LIBRARY_ROW)
        compose.onAllNodesWithTag(UiTestTags.LIBRARY_ROW)[0].performClick()

        // Speakers -> pick the first speaker -> startSession -> Now playing.
        compose.awaitTag(UiTestTags.SPEAKER_ROW)
        compose.onAllNodesWithTag(UiTestTags.SPEAKER_ROW)[0].performClick()

        // The first poll returns a playing session, so the control shows "Pause".
        compose.awaitContentDescription(str(R.string.nowplaying_action_pause))

        // Pause -> control flips to "Play".
        compose.onNodeWithContentDescription(str(R.string.nowplaying_action_pause)).performClick()
        compose.awaitContentDescription(str(R.string.nowplaying_action_play))

        // Resume -> back to "Pause".
        compose.onNodeWithContentDescription(str(R.string.nowplaying_action_play)).performClick()
        compose.awaitContentDescription(str(R.string.nowplaying_action_pause))

        // Seek to 120s (the fixture runs 0..600s). The position label must update to 2:00,
        // which proves the drag actually reached onSeek and moved the position - not a no-op.
        compose.onNodeWithTag(UiTestTags.NOWPLAYING_SEEK)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        compose.awaitText("2:00")

        // Stop -> back to the library.
        compose.onNodeWithContentDescription(str(R.string.nowplaying_action_stop)).performClick()
        compose.awaitTag(UiTestTags.LIBRARY_ROW)
    }

    @Test
    fun connectRejectsAnUnreachableServer() {
        compose.awaitText(str(R.string.connect_action_connect))
        // Nothing listens on :1, so certificate inspection fails -> the error/retry card.
        compose.onNode(hasSetTextAction()).performTextReplacement("https://localhost:1")
        compose.onNodeWithText(str(R.string.connect_action_connect)).performClick()
        // Wait longer than CertificateInspector's 15s connect timeout: if the port drops the
        // SYN instead of refusing it, inspection takes the full 15s to fail, so a 10s wait would
        // time out before the error/retry card appears.
        compose.awaitText(str(R.string.connect_action_retry), timeoutMillis = 20_000)
        compose.onNodeWithText(str(R.string.connect_action_retry)).assertIsDisplayed()
    }

    @Test
    fun signInFailureKeepsTheUserOnSignIn() {
        useDispatcher(RatatoskrDispatcher(login = { MockResponse().setResponseCode(401) }))
        connectTrustAndSubmitSignIn()
        // Login was rejected: the sign-in action returns (not submitting) and we did not
        // navigate to the library.
        compose.awaitText(str(R.string.signin_action))
        compose.onNodeWithText(str(R.string.signin_action)).assertIsDisplayed()
        compose.onAllNodesWithTag(UiTestTags.LIBRARY_ROW).assertCountEquals(0)
    }

    @Test
    fun emptyLibraryShowsTheEmptyState() {
        useDispatcher(RatatoskrDispatcher(libraryPage = WireFixtures.libraryPageJson(items = emptyList())))
        connectTrustAndSubmitSignIn()
        compose.awaitText(str(R.string.library_empty_title))
        compose.onAllNodesWithTag(UiTestTags.LIBRARY_ROW).assertCountEquals(0)
    }

    @Test
    fun signOutFromSettingsReturnsToSignIn() {
        connectTrustAndSubmitSignIn()
        compose.awaitTag(UiTestTags.LIBRARY_ROW)
        compose.onNodeWithContentDescription(str(R.string.library_settings)).performClick()
        compose.awaitText(str(R.string.settings_sign_out))
        compose.onNodeWithText(str(R.string.settings_sign_out)).performClick()
        // Signing out clears the tokens and routes back to sign-in.
        compose.awaitText(str(R.string.signin_action))
        compose.onNodeWithText(str(R.string.signin_action)).assertIsDisplayed()
    }

    @Test
    fun forgetCertificateFromSettingsClearsThePin() {
        connectTrustAndSubmitSignIn()
        compose.awaitTag(UiTestTags.LIBRARY_ROW)
        compose.onNodeWithContentDescription(str(R.string.library_settings)).performClick()
        compose.awaitText(str(R.string.settings_forget_cert))
        compose.onNodeWithText(str(R.string.settings_forget_cert)).performClick()
        // Core effect of "forget certificate": the pinned fingerprint is dropped, so the next
        // connection re-confirms it (SPEC section 6). The subsequent re-trust navigation back to
        // the connect screen is NOT asserted here: when Connect is the graph's start destination
        // (first-run session) that navigation is a pre-existing no-op, tracked separately.
        val container = ApplicationProvider.getApplicationContext<RatatoskrApp>().container
        compose.waitUntil(5_000) { runBlocking { container.connectionStore.fingerprint() } == null }
    }
}
