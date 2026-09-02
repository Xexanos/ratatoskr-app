/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.auth

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sign-in screen's two behaviours the goldens cannot see: whether the trust chip is there at
 * all, and what the visibility toggle does to the password. A golden freezes one frame; these are
 * about which frame the screen chooses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int, vararg args: Any): String =
        RuntimeEnvironment.getApplication().getString(id, *args)

    private fun signIn(serverHost: String?) {
        compose.setContent {
            RatatoskrTheme {
                SignInScreen(state = SignInUiState.Idle, serverHost = serverHost) { _, _ -> }
            }
        }
    }

    private val passwordField = hasSetTextAction() and hasImeAction(ImeAction.Done)

    // Matches on what the field actually renders. The raw value stays in the node's InputText
    // either way, so the ordinary text matchers cannot tell masked from revealed.
    private fun rendersPlainly(password: String) =
        SemanticsMatcher("the field renders '$password' unmasked") { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == password
        }

    @Test
    fun `a trusted host is stated on the chip`() {
        signIn(serverHost = "ratatoskr.home.arpa:8443")

        compose
            .onNodeWithText(str(R.string.signin_server_trusted, "ratatoskr.home.arpa:8443"))
            .assertIsDisplayed()
    }

    @Test
    fun `no readable host means no chip, not a placeholder`() {
        signIn(serverHost = null)

        compose.onAllNodesWithText(str(R.string.signin_server_trusted, ""), substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun `the password is masked until the toggle reveals it`() {
        signIn(serverHost = null)
        compose.onNode(passwordField).performTextInput("a-long-generated-password")

        // Masked: the toggle offers the reveal, and the characters are not on screen.
        compose.onNode(passwordField).assert(rendersPlainly("a-long-generated-password").not())
        compose.onNodeWithContentDescription(str(R.string.signin_password_show)).performClick()

        compose.onNode(passwordField).assert(rendersPlainly("a-long-generated-password"))
        // And the toggle now offers the way back, so the label always names this tap's action.
        compose.onNodeWithContentDescription(str(R.string.signin_password_hide)).assertIsDisplayed()
    }

    @Test
    fun `the toggle masks the password again`() {
        signIn(serverHost = null)
        compose.onNode(passwordField).performTextInput("a-long-generated-password")

        compose.onNodeWithContentDescription(str(R.string.signin_password_show)).performClick()
        compose.onNodeWithContentDescription(str(R.string.signin_password_hide)).performClick()

        compose.onNode(passwordField).assert(rendersPlainly("a-long-generated-password").not())
        compose.onNodeWithContentDescription(str(R.string.signin_password_show)).assertIsDisplayed()
    }
}
