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
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NowPlayingViewModelTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

    // A real, single confined thread - not UnconfinedTestDispatcher. pause()/stop() launch on
    // viewModelScope and their network call completes on one of OkHttp's own worker threads;
    // with an Unconfined dispatcher the ViewModel's state update would run inline on THAT OkHttp
    // callback thread, which can end up being the very thread this test's own waitUntil() is
    // blocking on. Confining Main to its own thread keeps ViewModel state mutation independent
    // of whichever thread happens to deliver a given HTTP response.
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun setUp() = Dispatchers.setMain(mainThread)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThread.close()
    }

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun trustedConnectionManager(): ConnectionManager {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        val store = DataStoreConnectionStore(dataStore)
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return ConnectionManager(store, FakeTokenAccess())
    }

    // pause()/stop() only launch on viewModelScope and return immediately; poll instead of
    // asserting the very next line.
    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `refresh populates the current session`() = runBlocking {
        server.dispatch { jsonResponse(WireFixtures.sessionJson(state = "playing")) }
        val viewModel = NowPlayingViewModel(trustedConnectionManager())

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertEquals(PlaybackState.PLAYING, state.session?.state)
        assertEquals(false, state.loading)
        assertNull(state.error)
    }

    @Test
    fun `refresh with no active session clears loading without an error`() = runBlocking {
        server.dispatch {
            jsonResponse("""{"code":"no_active_session","message":"Nothing playing"}""", code = 404)
        }
        val viewModel = NowPlayingViewModel(trustedConnectionManager())

        viewModel.refresh()

        val state = viewModel.uiState.value
        assertNull(state.session)
        assertEquals(false, state.loading)
        assertNull(state.error)
    }

    @Test
    fun `refresh failure surfaces the mapped error`() = runBlocking {
        server.dispatch {
            jsonResponse("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""", code = 502)
        }
        val viewModel = NowPlayingViewModel(trustedConnectionManager())

        viewModel.refresh()

        assertEquals("Audiobookshelf down", viewModel.uiState.value.error)
    }

    @Test
    fun `stop flips stopped and a subsequent refresh becomes a no-op`() = runBlocking {
        val getRequests = AtomicInteger(0)
        server.dispatch { request ->
            when {
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> {
                    getRequests.incrementAndGet()
                    jsonResponse(WireFixtures.sessionJson(state = "playing"))
                }
            }
        }
        val viewModel = NowPlayingViewModel(trustedConnectionManager())
        viewModel.refresh()
        assertEquals(1, getRequests.get())

        viewModel.stop()
        waitUntil { viewModel.uiState.value.stopped }

        // A poll that would land after stop() must not even reach the server, let alone revive
        // the session the user just ended (the `stopped` guard at the top of refresh()).
        viewModel.refresh()
        assertEquals(1, getRequests.get())
    }

    @Test
    fun `a stale in-flight poll does not revert a newer control action`() = runBlocking {
        val pollRequested = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        server.dispatch { request ->
            when {
                request.method == "GET" -> {
                    pollRequested.countDown()
                    // Simulate a poll that was already in flight when the user paused: it only
                    // resolves (with the now-stale "playing" state) after the pause has landed.
                    releasePoll.await(5, TimeUnit.SECONDS)
                    jsonResponse(WireFixtures.sessionJson(state = "playing"))
                }
                request.path.orEmpty().endsWith("/pause") -> jsonResponse(WireFixtures.sessionJson(state = "paused"))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val viewModel = NowPlayingViewModel(trustedConnectionManager())

        // Dispatchers.IO, not the default (this runBlocking's own confined thread): the very
        // next line blocks that thread with a plain (non-suspending) CountDownLatch.await(), so
        // a child coroutine sharing it would never get scheduled to even issue its request.
        val pollJob = launch(Dispatchers.IO) { viewModel.refresh() }
        assertTrue("poll never reached the server", pollRequested.await(5, TimeUnit.SECONDS))

        viewModel.pause()
        waitUntil { viewModel.uiState.value.session?.state == PlaybackState.PAUSED }

        releasePoll.countDown()
        pollJob.join()

        // The poll's stale "playing" result must not overwrite the pause the user issued while
        // it was in flight (the commandEpoch guard - see the kdoc on NowPlayingViewModel).
        assertEquals(PlaybackState.PAUSED, viewModel.uiState.value.session?.state)
    }
}
