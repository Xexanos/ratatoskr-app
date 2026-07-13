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
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
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
    private fun trustedConnectionManager(): ConnectionManager {
        val store = connectionStore()
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return ConnectionManager(store, FakeTokenAccess())
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
        server.enqueueJson(WireFixtures.authTokensJson())
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

        assertEquals(SignInUiState.Error("No server configured."), viewModel.uiState.value)
    }
}
