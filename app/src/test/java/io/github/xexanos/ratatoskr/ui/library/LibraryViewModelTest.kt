/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.ui.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.Collections

class LibraryViewModelTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

    // Standard, not Unconfined: the debounce test drives virtual time explicitly to prove
    // rapid keystrokes collapse into a single request instead of one per keystroke.
    private val dispatcher = StandardTestDispatcher()

    private val receivedQueries = Collections.synchronizedList(mutableListOf<String?>())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.dispatch { request ->
            val q = request.requestUrl?.queryParameter("q")
            receivedQueries += q
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    WireFixtures.libraryPageJson(items = listOf(WireFixtures.libraryItemSummaryJson(title = q ?: "all"))),
                )
        }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun trustedConnectionManager(): ConnectionManager {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        // A real dispatcher, not the shared virtual `dispatcher`: this store is seeded via
        // runBlocking below, before the virtual scheduler is ever pumped - sharing the virtual
        // scheduler here would deadlock (nothing would ever advance it to service the write).
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        val store = DataStoreConnectionStore(dataStore)
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return ConnectionManager(store, FakeTokenAccess())
    }

    // The debounce delay is virtual (driven by dispatcher.scheduler), but the actual HTTP round
    // trip once debounce elapses is real (OkHttp's own thread pool) - and its result only gets
    // applied to state once the resumed continuation is next pumped on the virtual scheduler.
    // This alternates draining the virtual queue with a short real wait until `condition` holds.
    private fun settleState(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            dispatcher.scheduler.advanceUntilIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `initial load fetches the full library without a query`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(trustedConnectionManager())

        settleState { viewModel.uiState.value.items.isNotEmpty() }

        assertEquals(listOf(null), receivedQueries)
        assertEquals(1, viewModel.uiState.value.items.size)
    }

    @Test
    fun `rapid keystrokes are debounced into a single request for the final query`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() } // settle the initial (null) load

        viewModel.search("cat")
        dispatcher.scheduler.advanceTimeBy(100)
        viewModel.search("dog")
        dispatcher.scheduler.advanceTimeBy(100)
        viewModel.search("fox")
        dispatcher.scheduler.advanceTimeBy(299)

        // Still within 300ms of the last keystroke: no new request fired yet.
        assertEquals(listOf(null), receivedQueries)

        dispatcher.scheduler.advanceTimeBy(2)
        settleState { receivedQueries.size == 2 }

        assertEquals(listOf(null, "fox"), receivedQueries)
        settleState { viewModel.uiState.value.items.singleOrNull()?.title == "fox" }
    }

    @Test
    fun `clearing the search box reissues the full library load`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() }

        viewModel.search("cat")
        settleState { receivedQueries.size == 2 }
        viewModel.search("")
        settleState { receivedQueries.size == 3 }

        // A blank query debounces at 0ms, same as the initial load - it is not throttled like a
        // real keystroke because there is nothing left to type past.
        assertEquals(listOf(null, "cat", null), receivedQueries)
    }

    @Test
    fun `no configured server surfaces a specific error`() = runTest(dispatcher) {
        val store = DataStoreConnectionStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher)) {
                tempFolder.root.resolve("unconfigured_${System.nanoTime()}.preferences_pb")
            },
        )
        val viewModel = LibraryViewModel(ConnectionManager(store, FakeTokenAccess()))

        settleState { viewModel.uiState.value.error != null }

        assertEquals("No server configured.", viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun `a failing request surfaces the mapped error`() = runTest(dispatcher) {
        server.dispatch {
            MockResponse().setResponseCode(502)
                .setBody("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""")
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())

        settleState { viewModel.uiState.value.error != null }

        assertEquals("Audiobookshelf down", viewModel.uiState.value.error)
    }
}
