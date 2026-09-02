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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sign-in screen's behaviours the goldens cannot see: whether the trust chip is there at
 * all, what the visibility toggle does to the password, and whether a reported failure is
 * actually on screen. A golden freezes one frame; these are about which frame the screen chooses.
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

    // The cramped case the pinned action creates: a form long enough to outgrow a short viewport,
    // so what sits at its end is only reachable further down the scroll region (issue #154).
    private fun crampedSignIn(state: SignInUiState) {
        compose.setContent {
            RatatoskrTheme {
                SignInScreen(
                    state = state,
                    initialUsername = "alex",
                    notice = SignInNotice.SESSION_ENDED,
                    serverHost = "ratatoskr.home.arpa",
                ) { _, _ -> }
            }
        }
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

    // Sighted-user cover for what liveRegion already tells TalkBack: the pinned button does not
    // scroll, so without bringing the banner into view a failed sign-in changes nothing the user
    // can see (issue #154).
    @Test
    @Config(qualifiers = "w360dp-h600dp")
    fun `a failed sign-in puts its error on screen even where the form must scroll`() {
        crampedSignIn(SignInUiState.Error(UiError.WrongCredentials))

        compose.onNodeWithText(str(R.string.error_wrong_credentials)).assertIsDisplayed()
    }

    // The hairline is the only sign that the form continues behind the pinned action, so it has
    // to be there exactly when it does.
    @Test
    @Config(qualifiers = "w360dp-h600dp")
    fun `a form that continues behind the pinned action closes with a hairline`() {
        crampedSignIn(SignInUiState.Idle)

        compose.onNodeWithTag(UiTestTags.SCROLL_BOUNDARY).assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `a form that fits draws no boundary to scroll past`() {
        signIn(serverHost = "ratatoskr.home.arpa")

        compose.onNodeWithTag(UiTestTags.SCROLL_BOUNDARY).assertDoesNotExist()
    }

    // Bringing the error into view scrolls the form to its end, so there is nothing left behind
    // the action to promise. The form's trailing padding has to stay outside the scroll for this
    // to hold - inside, it is content, and the line would claim more below than whitespace.
    @Test
    @Config(qualifiers = "w360dp-h600dp")
    fun `the boundary goes once the form is scrolled to its end`() {
        crampedSignIn(SignInUiState.Error(UiError.WrongCredentials))

        compose.onNodeWithTag(UiTestTags.SCROLL_BOUNDARY).assertDoesNotExist()
    }
}
