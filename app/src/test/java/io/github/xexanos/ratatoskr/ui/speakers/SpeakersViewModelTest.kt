/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.ui.speakers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeakersViewModelTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun trustedConnectionManager(): ConnectionManager {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher)) { file }
        val store = ConnectionStore(dataStore)
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return ConnectionManager(store, FakeTokenAccess())
    }

    private fun unconfiguredConnectionManager(): ConnectionManager {
        val file = tempFolder.root.resolve("unconfigured_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher)) { file }
        return ConnectionManager(ConnectionStore(dataStore), FakeTokenAccess())
    }

    // loadSpeakers()/start() only launch on viewModelScope and return immediately; the actual
    // HTTP call runs on OkHttp's real thread pool independent of the Main test dispatcher, so
    // the result lands asynchronously in real wall-clock time. Poll instead of asserting the
    // very next line.
    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `initial load fetches the speaker list`() = runTest(dispatcher) {
        server.dispatch { jsonResponse(WireFixtures.speakerListJson()) }

        val viewModel = SpeakersViewModel(trustedConnectionManager(), itemId = "i1")
        waitUntil { !viewModel.uiState.value.loading }

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(1, state.speakers.size)
    }

    @Test
    fun `no configured server surfaces a specific error`() = runTest(dispatcher) {
        val viewModel = SpeakersViewModel(unconfiguredConnectionManager(), itemId = "i1")
        waitUntil { !viewModel.uiState.value.loading }

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("No server configured.", state.error)
    }

    @Test
    fun `a failing request surfaces the mapped error`() = runTest(dispatcher) {
        server.dispatch {
            MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","message":"no"}""")
        }

        val viewModel = SpeakersViewModel(trustedConnectionManager(), itemId = "i1")
        waitUntil { !viewModel.uiState.value.loading }

        assertEquals("Sign-in expired. Please sign in again.", viewModel.uiState.value.error)
    }

    @Test
    fun `starting a session on a speaker moves to started`() = runTest(dispatcher) {
        server.dispatch { request ->
            when {
                request.path.orEmpty().startsWith("/v1/speakers") -> jsonResponse(WireFixtures.speakerListJson())
                request.path.orEmpty().startsWith("/v1/sessions/current") && request.method == "PUT" ->
                    jsonResponse(WireFixtures.sessionJson())
                else -> MockResponse().setResponseCode(404)
            }
        }
        val viewModel = SpeakersViewModel(trustedConnectionManager(), itemId = "i1")
        waitUntil { !viewModel.uiState.value.loading }

        viewModel.start("s1")
        waitUntil { !viewModel.uiState.value.starting }

        val state = viewModel.uiState.value
        assertTrue(state.started)
        assertFalse(state.starting)
    }

    @Test
    fun `a failing start surfaces an error and stops starting`() = runTest(dispatcher) {
        server.dispatch { request ->
            when {
                request.path.orEmpty().startsWith("/v1/speakers") -> jsonResponse(WireFixtures.speakerListJson())
                request.path.orEmpty().startsWith("/v1/sessions/current") && request.method == "PUT" ->
                    MockResponse().setResponseCode(502).setBody(
                        """{"code":"abs_unreachable","message":"Audiobookshelf down"}""",
                    )
                else -> MockResponse().setResponseCode(404)
            }
        }
        val viewModel = SpeakersViewModel(trustedConnectionManager(), itemId = "i1")
        waitUntil { !viewModel.uiState.value.loading }

        viewModel.start("s1")
        waitUntil { !viewModel.uiState.value.starting }

        val state = viewModel.uiState.value
        assertFalse(state.starting)
        assertFalse(state.started)
        assertEquals("Audiobookshelf down", state.error)
    }
}
