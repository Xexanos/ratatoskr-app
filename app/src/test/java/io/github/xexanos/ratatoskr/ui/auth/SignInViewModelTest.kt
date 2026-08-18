/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.ui.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import io.github.xexanos.ratatoskr.ui.UiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignInViewModelTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun connectionStore(): ConnectionStore {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher)) { file }
        return DataStoreConnectionStore(dataStore)
    }

    /** A [ConnectionManager] whose client() resolves against [server] (trusted server config saved). */
    private fun trustedConnectionManager(tokens: FakeTokenAccess = FakeTokenAccess()): ConnectionManager {
        val store = connectionStore()
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return ConnectionManager(store, tokens)
    }

    private fun unconfiguredConnectionManager(): ConnectionManager =
        ConnectionManager(connectionStore(), FakeTokenAccess())

    // signIn() only launches on viewModelScope and returns immediately; the actual login call
    // runs on OkHttp's real thread pool independent of the Main test dispatcher, so the result
    // lands asynchronously in real wall-clock time. Poll instead of asserting the very next line.
    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `blank username is a no-op`() = runTest(dispatcher) {
        val viewModel = SignInViewModel(trustedConnectionManager())

        viewModel.signIn("", "secret")

        assertEquals(SignInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `blank password is a no-op`() = runTest(dispatcher) {
        val viewModel = SignInViewModel(trustedConnectionManager())

        viewModel.signIn("alex", "")

        assertEquals(SignInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `a successful login moves to Success`() = runTest(dispatcher) {
        server.enqueueJson(WireFixtures.authSessionJson())
        val viewModel = SignInViewModel(trustedConnectionManager())

        viewModel.signIn("alex", "secret")
        waitUntil { viewModel.uiState.value != SignInUiState.Submitting }

        assertEquals(SignInUiState.Success, viewModel.uiState.value)
    }

    @Test
    fun `a rejected login surfaces an error and does not report Success`() = runTest(dispatcher) {
        server.server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","message":"no"}"""),
        )
        val viewModel = SignInViewModel(trustedConnectionManager())

        viewModel.signIn("alex", "wrong")
        waitUntil { viewModel.uiState.value != SignInUiState.Submitting }

        val state = viewModel.uiState.value
        assertTrue("expected Error, was $state", state is SignInUiState.Error)
    }

    @Test
    fun `no configured server surfaces a specific error`() = runTest(dispatcher) {
        val viewModel = SignInViewModel(unconfiguredConnectionManager())

        viewModel.signIn("alex", "secret")
        waitUntil { viewModel.uiState.value != SignInUiState.Submitting }

        assertEquals(SignInUiState.Error(UiError.NoServer), viewModel.uiState.value)
    }

    @Test
    fun `an UPSTREAM_SESSION_LOST reauth pre-fills the username and shows the media-server notice`() =
        runTest(dispatcher) {
            // The 401 recovery (SPEC section 5): the token is gone but the username survives, and the
            // code selects the media-server notice. Password is never pre-filled - the screen owns it.
            val connectionManager = trustedConnectionManager(FakeTokenAccess("stale", AuthUser("7", "alex")))
            connectionManager.requireReauth("UPSTREAM_SESSION_LOST")

            val viewModel = SignInViewModel(connectionManager)
            waitUntil { viewModel.prefill.value.username == "alex" }

            assertEquals("alex", viewModel.prefill.value.username)
            assertEquals(SignInNotice.MEDIA_SERVER_EXPIRED, viewModel.prefill.value.notice)
        }

    @Test
    fun `any other 401 code shows the session-ended notice`() = runTest(dispatcher) {
        val connectionManager = trustedConnectionManager(FakeTokenAccess("stale", AuthUser("7", "alex")))
        connectionManager.requireReauth(null) // a 401 with no distinguishing code

        val viewModel = SignInViewModel(connectionManager)
        waitUntil { viewModel.prefill.value.username == "alex" }

        assertEquals(SignInNotice.SESSION_ENDED, viewModel.prefill.value.notice)
    }

    @Test
    fun `the one-time migration shows the app-updated notice with the username pre-filled`() =
        runTest(dispatcher) {
            // First launch after the /v1 -> /v2 update (SPEC section 5, issue #121): the legacy
            // tokens were discarded at launch, the username survives, and the one-time notice
            // explains the re-login.
            val connectionManager =
                trustedConnectionManager(FakeTokenAccess(user = AuthUser("7", "alex"), legacyTokens = true))
            connectionManager.migrateFromV1()

            val viewModel = SignInViewModel(connectionManager)
            waitUntil { viewModel.prefill.value.username == "alex" }

            assertEquals(SignInNotice.APP_UPDATED, viewModel.prefill.value.notice)
        }

    @Test
    fun `an ordinary sign-in visit shows no notice`() = runTest(dispatcher) {
        // No reauth is pending: the username may still pre-fill (a remembered user), but there is no
        // explanatory notice.
        val connectionManager = trustedConnectionManager(FakeTokenAccess("live", AuthUser("7", "alex")))

        val viewModel = SignInViewModel(connectionManager)
        waitUntil { viewModel.prefill.value.username == "alex" }

        assertNull(viewModel.prefill.value.notice)
    }
}
