/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.connect

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.network.domain.CertificateInfo
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.OffsetDateTime

/**
 * Which action the pinned slot offers, per state (issue #155). The goldens freeze how one state
 * looks; these pin the mapping itself - and that no state's action drifts back into the
 * certificate card, where it fell below the fold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val cert = CertificateInfo(
        subject = "CN=ratatoskr.home",
        issuer = "CN=ratatoskr.home",
        notBefore = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        notAfter = OffsetDateTime.parse("2027-01-01T00:00:00Z"),
        sha256Fingerprint = "ab:cd:ef:12:34:56:78:90",
    )

    private fun str(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun connect(
        state: ConnectUiState,
        onConfirm: (String, String) -> Unit = { _, _ -> },
        onReset: () -> Unit = {},
    ) {
        compose.setContent {
            RatatoskrTheme {
                ConnectScreen(state = state, onInspect = {}, onConfirm = onConfirm, onReset = onReset)
            }
        }
    }

    private fun assertAbsent(vararg ids: Int) = ids.forEach {
        compose.onAllNodesWithText(str(it)).assertCountEquals(0)
    }

    @Test
    fun idleOffersConnect() {
        connect(ConnectUiState.Idle)

        compose.onNodeWithText(str(R.string.connect_action_connect)).assertIsDisplayed()
        assertAbsent(
            R.string.connect_action_trust,
            R.string.connect_action_cancel,
            R.string.connect_action_retry,
        )
    }

    // The wait has no action of its own: a disabled Connect button here would fail the contrast
    // floor (AccessibilityChecksTest), and the knot loader already carries the wait.
    @Test
    fun inspectingOffersNoAction() {
        connect(ConnectUiState.Inspecting)

        assertAbsent(
            R.string.connect_action_connect,
            R.string.connect_action_trust,
            R.string.connect_action_cancel,
            R.string.connect_action_retry,
        )
    }

    @Test
    fun confirmOffersTrustAndCancelInsteadOfConnect() {
        connect(ConnectUiState.Confirm("https://ratatoskr.home:8080", cert))

        compose.onNodeWithText(str(R.string.connect_action_trust)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.connect_action_cancel)).assertIsDisplayed()
        assertAbsent(R.string.connect_action_connect, R.string.connect_action_retry)
    }

    // "Once the certificate shows, the brand steps aside" (ux-design: Connect, decision 4).
    @Test
    fun confirmDropsTheWelcomeBlock() {
        connect(ConnectUiState.Confirm("https://ratatoskr.home:8080", cert))

        assertAbsent(R.string.connect_welcome_title, R.string.connect_welcome_subtitle)
    }

    @Test
    fun idleKeepsTheWelcomeBlock() {
        connect(ConnectUiState.Idle)

        compose.onNodeWithText(str(R.string.connect_welcome_title)).assertIsDisplayed()
    }

    // Why dropping the welcome block is not cosmetic, at the smallest height the design draws
    // for: the whole certificate - fingerprint and the instruction to compare it before trusting
    // it, which decision 2 rests on - has to be on screen at the same time as the copper action
    // that acts on it. With the welcome block's 330 dp still in the scroll region, the compare
    // hint sat below the fold.
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h600dp")
    fun confirmShowsTheWholeCertificateAndBothActionsWithoutScrolling() {
        connect(ConnectUiState.Confirm("https://ratatoskr.home:8080", cert))

        compose.onNodeWithText(cert.sha256Fingerprint).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.connect_cert_compare_hint)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.connect_action_trust)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.connect_action_cancel)).assertIsDisplayed()
    }

    @Test
    fun trustPinsTheFingerprintOfTheCertificateOnScreen() {
        var trusted: Pair<String, String>? = null
        connect(
            ConnectUiState.Confirm("https://ratatoskr.home:8080", cert),
            onConfirm = { baseUrl, fingerprint -> trusted = baseUrl to fingerprint },
        )

        compose.onNodeWithText(str(R.string.connect_action_trust)).performClick()

        assertEquals("https://ratatoskr.home:8080" to cert.sha256Fingerprint, trusted)
    }

    @Test
    fun cancelLeavesTheCertificateUntrusted() {
        var reset = false
        connect(
            ConnectUiState.Confirm("https://ratatoskr.home:8080", cert),
            onReset = { reset = true },
        )

        compose.onNodeWithText(str(R.string.connect_action_cancel)).performClick()

        assertTrue("Cancel did not reset the screen", reset)
    }

    // Asserted the way the slot earns its keep: the default test window is short enough that the
    // banner inside the scroll region is present but off screen, and Try again is on screen
    // anyway. Inline at the end of the scroller it would have gone with the banner.
    @Test
    fun errorOffersRetryWithoutScrolling() {
        connect(ConnectUiState.Error("Could not read the server certificate."))

        compose.onNodeWithText("Could not read the server certificate.").assertExists()
        compose.onNodeWithText(str(R.string.connect_action_retry)).assertIsDisplayed()
        assertAbsent(
            R.string.connect_action_connect,
            R.string.connect_action_trust,
            R.string.connect_action_cancel,
        )
    }
}
