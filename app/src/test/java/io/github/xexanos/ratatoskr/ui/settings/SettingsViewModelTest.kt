/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.FakeConnectionStore
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsViewModelTest {

    @get:Rule val tempFolder = TemporaryFolder()

    // Unconfined: these tests care about the end state after a VM action's suspend chain
    // (store write -> connectionManager.invalidate() -> state update) completes, not about
    // controlling interleaving. The ViewModel is driven against an in-memory FakeConnectionStore
    // whose reads and writes complete in-place, so eager execution settles each action
    // synchronously - no advanceUntilIdle() per test. (A real DataStore would not: since
    // datastore 1.2 its writes land on an IO dispatcher, and its file rename is flaky on Windows;
    // the round-trip test below covers the real store directly instead.)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // Step 2 smoke test (see task brief / docs/testing.md): a real Jetpack DataStore-Preferences
    // instance, pointed at a temp file, with no Context and no Robolectric. If this stops working
    // on a future dependency bump the ViewModel tests still pass against the fake, so the real
    // store's round-trip is asserted directly here.
    @Test
    fun `ConnectionStore round-trips a trusted server through a real DataStore on the JVM`() = runTest(dispatcher) {
        val store = realConnectionStore()

        store.saveTrustedServer("https://ratatoskr.home:8080", "ab:cd:ef")

        assertEquals("https://ratatoskr.home:8080", store.currentServerConfig()?.baseUrl)
        assertEquals("ab:cd:ef", store.fingerprint())
    }

    // Records cover-cache clears so each test can assert the wipe happened (or did not).
    private var coverCacheClears = 0
    private val clearCoverCache: suspend () -> Unit = { coverCacheClears++ }

    @Test
    fun `initial state reports no configured server`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(connectionManager(), clearCoverCache)

        assertNull(viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `initial state reports the trusted server's URL`() = runTest(dispatcher) {
        val store = FakeConnectionStore(baseUrl = "https://ratatoskr.home:8080", fingerprint = "ab:cd:ef")

        val viewModel = SettingsViewModel(connectionManager(store), clearCoverCache)

        assertEquals("https://ratatoskr.home:8080", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `forgetCertificate drops the fingerprint and flips certForgotten`() = runTest(dispatcher) {
        val store = FakeConnectionStore(baseUrl = "https://ratatoskr.home:8080", fingerprint = "ab:cd:ef")
        val viewModel = SettingsViewModel(connectionManager(store), clearCoverCache)

        viewModel.forgetCertificate()

        assertTrue(viewModel.uiState.value.certForgotten)
        assertNull(store.fingerprint())
        // The URL is kept for convenience (re-trust flow); only the fingerprint is forgotten.
        assertEquals("https://ratatoskr.home:8080", store.currentServerConfig()?.baseUrl)
        // A re-trusted server may be a different one; cached covers of the old library go too.
        assertEquals(1, coverCacheClears)
    }

    @Test
    fun `signOut clears the token store and flips signedOut`() = runTest(dispatcher) {
        val tokens = FakeTokenAccess(accessToken = "a1", refreshToken = "r1")
        val viewModel = SettingsViewModel(connectionManager(tokenStore = tokens), clearCoverCache)

        viewModel.signOut()

        assertTrue(viewModel.uiState.value.signedOut)
        assertNull(tokens.currentAccessTokenBlocking())
        // A signed-out device keeps no artwork of the library it left.
        assertEquals(1, coverCacheClears)
    }

    @Test
    fun `clearImageCache wipes the cover caches and raises the confirmation flag once`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(connectionManager(), clearCoverCache)

        viewModel.clearImageCache()

        assertEquals(1, coverCacheClears)
        assertTrue(viewModel.uiState.value.imageCacheCleared)

        viewModel.imageCacheClearedShown()

        assertEquals(false, viewModel.uiState.value.imageCacheCleared)
    }

    private fun realConnectionStore(): ConnectionStore {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher)) { file }
        return DataStoreConnectionStore(dataStore)
    }

    private fun connectionManager(
        connectionStore: ConnectionStore = FakeConnectionStore(),
        tokenStore: FakeTokenAccess = FakeTokenAccess(),
    ) = ConnectionManager(connectionStore, tokenStore)
}
