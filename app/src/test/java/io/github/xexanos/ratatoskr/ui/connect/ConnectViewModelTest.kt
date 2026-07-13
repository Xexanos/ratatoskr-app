/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.ui.connect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import io.github.xexanos.ratatoskr.network.tls.CertificateInspector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectViewModelTest {

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

    private fun viewModel(store: ConnectionStore = connectionStore()) = ConnectViewModel(
        inspector = CertificateInspector(),
        connectionStore = store,
        connectionManager = ConnectionManager(store, FakeTokenAccess()),
    )

    // inspect()/confirm() only launch on viewModelScope and return immediately; the certificate
    // fetch runs on a real OkHttp thread pool (CertificateInspector explicitly hops to
    // Dispatchers.IO) independent of the Main test dispatcher, so the result lands asynchronously
    // in real wall-clock time. Poll instead of asserting the very next line.
    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `inspecting a blank URL is a no-op`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.inspect("   ")

        assertEquals(ConnectUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `inspecting a reachable server moves to Confirm with its certificate`() = runTest(dispatcher) {
        server.enqueueJson("""{"reachable":true}""")
        val viewModel = viewModel()

        viewModel.inspect(server.baseUrl)
        waitUntil { viewModel.uiState.value !is ConnectUiState.Inspecting }

        val state = viewModel.uiState.value
        assertTrue("expected Confirm, was $state", state is ConnectUiState.Confirm)
        assertEquals(server.fingerprint, (state as ConnectUiState.Confirm).info.sha256Fingerprint)
    }

    @Test
    fun `inspecting an unreachable server surfaces an error`() = runTest(dispatcher) {
        val viewModel = viewModel()

        // Nothing listens on :1, so the TLS handshake itself fails.
        viewModel.inspect("https://localhost:1")
        waitUntil { viewModel.uiState.value !is ConnectUiState.Inspecting }

        val state = viewModel.uiState.value
        assertTrue("expected Error, was $state", state is ConnectUiState.Error)
    }

    @Test
    fun `confirming a certificate saves it, invalidates the client cache, and moves to Trusted`() =
        runTest(dispatcher) {
            val store = connectionStore()
            val viewModel = viewModel(store)

            viewModel.confirm("https://ratatoskr.home:8080", "ab:cd:ef")
            waitUntil { viewModel.uiState.value is ConnectUiState.Trusted }

            assertEquals(ConnectUiState.Trusted, viewModel.uiState.value)
            assertEquals("https://ratatoskr.home:8080", store.currentServerConfig()?.baseUrl)
            assertEquals("ab:cd:ef", store.fingerprint())
        }

    @Test
    fun `reset returns to Idle`() = runTest(dispatcher) {
        val store = connectionStore()
        val viewModel = viewModel(store)
        viewModel.confirm("https://ratatoskr.home:8080", "ab:cd:ef")
        waitUntil { viewModel.uiState.value is ConnectUiState.Trusted }

        viewModel.reset()

        assertEquals(ConnectUiState.Idle, viewModel.uiState.value)
        // reset() only clears the screen's own state; it does not undo the persisted trust.
        assertEquals("ab:cd:ef", store.fingerprint())
    }
}
