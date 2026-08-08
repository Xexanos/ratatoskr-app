/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.MainActivity
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.RatatoskrApp
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * SPEC section 9, layer 3 (like [AppFlowTest]): the one-time /v1 -> /v2 migration (SPEC
 * section 5, issue #121), driven through the real [MainActivity]. The persisted state of a
 * pre-update install - trusted server, certificate fingerprint, username, and the legacy
 * Audiobookshelf token pair - is seeded BEFORE the activity launches, so launch routing runs
 * the migration exactly as a user's first start after the update would.
 */
@RunWith(AndroidJUnit4::class)
class MigrationFlowTest {

    private val reset = ClearAppStateRule()
    private val server = HttpsMockServer()

    /**
     * A pre-update install's persisted state, written through the container the app itself
     * uses (its DataStores are process singletons - a second instance per file would crash).
     * The legacy keys go in raw exactly as the /v1 app left them - frozen history, deliberately
     * duplicated here rather than shared with TokenStore (like TokenStoreMigrationTest); their
     * values never need to decrypt because the migration discards ciphertext unread.
     */
    private val seedV1State = object : ExternalResource() {
        override fun before() {
            server.server.dispatcher = RatatoskrDispatcher()
            val container = ApplicationProvider.getApplicationContext<RatatoskrApp>().container
            runBlocking {
                container.connectionStore.saveTrustedServer(server.baseUrl, server.fingerprint)
                container.tokenDataStore.edit {
                    it[stringPreferencesKey("access_token")] = "ciphertext-access"
                    it[stringPreferencesKey("refresh_token")] = "ciphertext-refresh"
                    it[stringPreferencesKey("user_id")] = "7"
                    it[stringPreferencesKey("username")] = "alex"
                }
            }
        }
    }

    private val compose = createAndroidComposeRule<MainActivity>()

    // reset -> start the server -> seed the /v1 install's state -> launch MainActivity.
    @get:Rule
    val chain: RuleChain =
        RuleChain.outerRule(reset).around(server).around(seedV1State).around(compose)

    private fun str(id: Int): String = compose.activity.getString(id)

    @Test
    fun firstLaunchAfterTheUpdateLandsOnThePrefilledOneTimeReLogin() {
        // Not the connect screen: server URL and certificate fingerprint survived, so routing
        // skips straight to sign-in - with the one-time update notice and the surviving
        // username; only the password is asked for.
        compose.awaitText(str(R.string.signin_notice_app_updated))
        compose.onNodeWithText("alex").assertIsDisplayed()

        // Re-login with just the password mints a fresh Ratatoskr token and lands in the library.
        compose.onNode(hasSetTextAction() and hasImeAction(ImeAction.Done)).performTextInput("secret")
        compose.onNodeWithText(str(R.string.signin_action)).performClick()
        compose.awaitTag(UiTestTags.LIBRARY_ROW)

        // The migration ran once: the discarded legacy pair is gone for good, so nothing can
        // re-trigger it (TokenStoreMigrationTest pins the once-only store behaviour).
        val container = ApplicationProvider.getApplicationContext<RatatoskrApp>().container
        assertTrue(runBlocking { container.credentialStore.hasCredential() })
    }
}
