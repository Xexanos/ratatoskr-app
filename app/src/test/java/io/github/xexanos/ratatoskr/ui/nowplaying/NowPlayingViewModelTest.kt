/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.ui.nowplaying

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.data.SessionManager
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import io.github.xexanos.ratatoskr.ui.UiError
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors

class NowPlayingViewModelTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

    // A real, single confined thread - not UnconfinedTestDispatcher: control actions launch on
    // viewModelScope and their network call completes on an OkHttp worker thread, so state
    // mutation must not run inline on that callback thread (see SessionManagerTest).
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // NowPlayingViewModel now keeps a permanent sessionManager.state.collect{} subscription
    // alive in viewModelScope (ADR 0002) - in production that scope dies with the ViewModel
    // when the framework calls onCleared(), but nothing clears it here, so tearDown cancels it
    // explicitly before resetMain(): otherwise a leaked collector can still be resumed after
    // Main is unset (or reset to a later test's dispatcher) and throw on whichever test runs
    // next, since kotlinx-coroutines-test's Dispatchers.Main is a shared, mutable indirection.
    private val createdViewModels = mutableListOf<NowPlayingViewModel>()

    @Before fun setUp() = Dispatchers.setMain(mainThread)

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        mainThread.close()
    }

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sessionManager(): SessionManager {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        val store = DataStoreConnectionStore(dataStore)
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return SessionManager(ConnectionManager(store, FakeTokenAccess()))
    }

    private fun nowPlayingViewModel(manager: SessionManager): NowPlayingViewModel =
        NowPlayingViewModel(manager).also { createdViewModels += it }

    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the shared session state populates the screen`() = runBlocking {
        server.dispatch { jsonResponse(WireFixtures.sessionJson(state = "playing")) }
        val manager = sessionManager()
        manager.poll()

        val viewModel = nowPlayingViewModel(manager)
        waitUntil { !viewModel.uiState.value.loading }

        val state = viewModel.uiState.value
        assertEquals(PlaybackState.PLAYING, state.session?.state)
        assertNull(state.error)
    }

    @Test
    fun `no active session clears loading without an error`() = runBlocking {
        server.dispatch {
            jsonResponse("""{"code":"no_active_session","message":"Nothing playing"}""", code = 404)
        }
        val manager = sessionManager()
        manager.poll()

        val viewModel = nowPlayingViewModel(manager)
        waitUntil { !viewModel.uiState.value.loading }

        val state = viewModel.uiState.value
        assertNull(state.session)
        assertNull(state.error)
    }

    @Test
    fun `a poll failure surfaces the mapped error while keeping the session`() = runBlocking {
        server.dispatch { jsonResponse(WireFixtures.sessionJson(state = "playing")) }
        val manager = sessionManager()
        manager.poll()
        val viewModel = nowPlayingViewModel(manager)
        waitUntil { !viewModel.uiState.value.loading }

        server.dispatch { jsonResponse("""{"code":"upstream_error","message":"Sonos is unavailable"}""", code = 502) }
        manager.poll()

        waitUntil { viewModel.uiState.value.error != null }
        val state = viewModel.uiState.value
        assertEquals("Sonos is unavailable", ((state.error as UiError.Domain).error as RatatoskrError.Upstream).message)
        assertEquals(PlaybackState.PLAYING, state.session?.state)
    }

    @Test
    fun `a failed pause surfaces this screen's own error banner`() = runBlocking {
        server.dispatch { request ->
            when {
                request.path.orEmpty().endsWith("/pause") ->
                    MockResponse().setResponseCode(502)
                        .setBody("""{"code":"upstream_error","message":"Sonos is unavailable"}""")
                else -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
            }
        }
        val manager = sessionManager()
        manager.poll()
        val viewModel = nowPlayingViewModel(manager)
        waitUntil { !viewModel.uiState.value.loading }

        viewModel.pause()
        waitUntil { viewModel.uiState.value.error != null }

        val error = viewModel.uiState.value.error
        assertEquals("Sonos is unavailable", ((error as UiError.Domain).error as RatatoskrError.Upstream).message)
    }

    @Test
    fun `stop flips stopped once the session is cleared`() = runBlocking {
        server.dispatch { request ->
            when {
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
            }
        }
        val manager = sessionManager()
        manager.poll()
        val viewModel = nowPlayingViewModel(manager)
        waitUntil { !viewModel.uiState.value.loading }

        viewModel.stop()
        waitUntil { viewModel.uiState.value.stopped }

        assertNull(viewModel.uiState.value.session)
    }
}
