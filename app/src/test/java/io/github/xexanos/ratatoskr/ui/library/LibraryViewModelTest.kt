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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `loadMore follows the cursor and appends the next page`() = runTest(dispatcher) {
        val receivedCursors = Collections.synchronizedList(mutableListOf<String?>())
        server.dispatch { request ->
            val cursor = request.requestUrl?.queryParameter("cursor")
            receivedCursors += cursor
            val body = if (cursor == null) {
                WireFixtures.libraryPageJson(
                    items = listOf(WireFixtures.libraryItemSummaryJson(id = "i1", title = "page one")),
                    nextCursor = "c2",
                )
            } else {
                WireFixtures.libraryPageJson(
                    items = listOf(WireFixtures.libraryItemSummaryJson(id = "i2", title = "page two")),
                )
            }
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() }
        assertEquals("c2", viewModel.uiState.value.nextCursor)

        viewModel.loadMore()
        settleState { viewModel.uiState.value.items.size == 2 }

        assertEquals(listOf(null, "c2"), receivedCursors)
        assertEquals(listOf("page one", "page two"), viewModel.uiState.value.items.map { it.title })
        assertEquals(null, viewModel.uiState.value.nextCursor)

        // The last page is in: further loadMore calls must not hit the server again.
        viewModel.loadMore()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, receivedCursors.size)
    }

    @Test
    fun `a failing next page keeps the list and flags a retryable error`() = runTest(dispatcher) {
        var failNextPage = true
        server.dispatch { request ->
            val cursor = request.requestUrl?.queryParameter("cursor")
            if (cursor != null && failNextPage) {
                MockResponse().setResponseCode(502)
                    .setBody("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""")
            } else {
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        WireFixtures.libraryPageJson(
                            items = listOf(
                                WireFixtures.libraryItemSummaryJson(id = "i-$cursor", title = "page after $cursor"),
                            ),
                            nextCursor = if (cursor == null) "c2" else null,
                        ),
                    )
            }
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() }

        viewModel.loadMore()
        settleState { viewModel.uiState.value.loadMoreError }

        // The loaded page survives, the full-screen error stays clear, and the cursor is kept
        // so the page can be retried.
        assertEquals(1, viewModel.uiState.value.items.size)
        assertEquals(null, viewModel.uiState.value.error)
        assertEquals("c2", viewModel.uiState.value.nextCursor)

        failNextPage = false
        viewModel.loadMore()
        settleState { viewModel.uiState.value.items.size == 2 }

        assertTrue(!viewModel.uiState.value.loadMoreError)
        assertEquals(null, viewModel.uiState.value.nextCursor)
    }

    @Test
    fun `a new query restarts pagination from the first page`() = runTest(dispatcher) {
        server.dispatch { request ->
            val cursor = request.requestUrl?.queryParameter("cursor")
            val q = request.requestUrl?.queryParameter("q")
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    WireFixtures.libraryPageJson(
                        items = listOf(
                            WireFixtures.libraryItemSummaryJson(id = "$q-$cursor", title = q ?: "all"),
                        ),
                        // Every page claims a successor, so the cursor is non-null when the
                        // query changes - the fresh load must not carry it over.
                        nextCursor = "next-after-$cursor",
                    ),
                )
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() }
        viewModel.loadMore()
        settleState { viewModel.uiState.value.items.size == 2 }

        viewModel.search("cat")
        settleState { viewModel.uiState.value.items.singleOrNull()?.title == "cat" }

        // The cat load starts over: one page, requested without a cursor (id "cat-null").
        assertEquals("cat-null", viewModel.uiState.value.items.single().id)
    }

    @Test
    fun `a query change cancels an in-flight page load so its stale page never appends`() = runTest(dispatcher) {
        // The next-page response is held until the test releases it, so the query changes while
        // loadNextPage is genuinely suspended on the server - the actual race the collectLatest
        // structure protects against (unlike the test above, which changes query only after the
        // page has fully landed).
        val pageBlocked = CountDownLatch(1)
        server.dispatch { request ->
            val cursor = request.requestUrl?.queryParameter("cursor")
            val q = request.requestUrl?.queryParameter("q")
            receivedQueries += q
            when {
                cursor != null -> {
                    pageBlocked.await()
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            WireFixtures.libraryPageJson(
                                items = listOf(WireFixtures.libraryItemSummaryJson(id = "stale", title = "stale page")),
                            ),
                        )
                }
                else -> MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        WireFixtures.libraryPageJson(
                            items = listOf(WireFixtures.libraryItemSummaryJson(id = "${q}-first", title = q ?: "all")),
                            nextCursor = if (q == null) "c2" else null,
                        ),
                    )
            }
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.nextCursor == "c2" }

        // Kick off the next page and wait until it is actually in flight (request received,
        // now parked on the latch), then change the query while it hangs.
        viewModel.loadMore()
        settleState { viewModel.uiState.value.loadingMore }
        viewModel.search("cat")
        dispatcher.scheduler.advanceTimeBy(301) // clear the 300ms search debounce
        settleState { viewModel.uiState.value.items.singleOrNull()?.title == "cat" }

        // Release the held page and give it a chance to (wrongly) append.
        pageBlocked.countDown()
        repeat(5) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }

        // collectLatest cancelled the null-query page load, so its "stale" item never landed.
        assertEquals(listOf("cat-first"), viewModel.uiState.value.items.map { it.id })
        assertTrue(viewModel.uiState.value.items.none { it.id == "stale" })
    }

    @Test
    fun `refresh reloads the current query in place`() = runTest(dispatcher) {
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() } // initial (null) load

        viewModel.search("cat")
        settleState { viewModel.uiState.value.items.singleOrNull()?.title == "cat" }
        val before = receivedQueries.size

        viewModel.refresh()
        settleState { receivedQueries.size == before + 1 }

        // Reloaded the SAME query (cat), not reset to the full library.
        assertEquals("cat", receivedQueries.last())
        assertEquals("cat", viewModel.uiState.value.items.single().title)
    }

    @Test
    fun `retry after a failed load re-issues the load and clears the error`() = runTest(dispatcher) {
        val failFirst = AtomicBoolean(true)
        server.dispatch { request ->
            val q = request.requestUrl?.queryParameter("q")
            receivedQueries += q
            if (failFirst.getAndSet(false)) {
                MockResponse().setResponseCode(502)
                    .setBody("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""")
            } else {
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(WireFixtures.libraryPageJson(items = listOf(WireFixtures.libraryItemSummaryJson(title = q ?: "all"))))
            }
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.error != null }
        assertTrue(viewModel.uiState.value.items.isEmpty())

        viewModel.refresh()
        settleState { viewModel.uiState.value.items.isNotEmpty() }

        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `a failed refresh keeps the list and surfaces a one-shot refreshError`() = runTest(dispatcher) {
        val failNow = AtomicBoolean(false)
        server.dispatch { request ->
            val q = request.requestUrl?.queryParameter("q")
            receivedQueries += q
            if (failNow.get()) {
                MockResponse().setResponseCode(502)
                    .setBody("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""")
            } else {
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(WireFixtures.libraryPageJson(items = listOf(WireFixtures.libraryItemSummaryJson(title = q ?: "all"))))
            }
        }
        val viewModel = LibraryViewModel(trustedConnectionManager())
        settleState { viewModel.uiState.value.items.isNotEmpty() }

        failNow.set(true)
        viewModel.refresh()
        settleState { viewModel.uiState.value.refreshError != null }

        // The list survives; the failure is a one-shot, not the full-screen error state.
        assertEquals(1, viewModel.uiState.value.items.size)
        assertEquals(null, viewModel.uiState.value.error)
        assertTrue(!viewModel.uiState.value.refreshing)

        viewModel.clearRefreshError()
        assertEquals(null, viewModel.uiState.value.refreshError)
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
