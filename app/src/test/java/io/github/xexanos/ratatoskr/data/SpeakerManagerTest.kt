/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpeakerManagerTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

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

    @Test
    fun `nameFor resolves a known id after fetching the speaker list`() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatch {
            requests.incrementAndGet()
            jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
        }
        val manager = SpeakerManager(trustedConnectionManager())

        assertEquals("Living Room", manager.nameFor("s1"))
        assertEquals(1, requests.get())
    }

    @Test
    fun `a second lookup for a known id does not refetch`() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatch {
            requests.incrementAndGet()
            jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
        }
        val manager = SpeakerManager(trustedConnectionManager())

        manager.nameFor("s1")
        manager.nameFor("s1")

        assertEquals(1, requests.get())
    }

    @Test
    fun `a lookup for an unknown id refetches the list once`() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatch {
            when (requests.incrementAndGet()) {
                1 -> jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
                else -> jsonResponse(
                    WireFixtures.speakerListJson(
                        WireFixtures.speakerJson(id = "s1", name = "Living Room"),
                        WireFixtures.speakerJson(id = "s2", name = "Kitchen"),
                    ),
                )
            }
        }
        val manager = SpeakerManager(trustedConnectionManager())

        assertEquals("Living Room", manager.nameFor("s1")) // first fetch
        assertEquals("Kitchen", manager.nameFor("s2")) // miss -> refetch
        assertEquals(2, requests.get())

        manager.nameFor("s2") // now cached
        assertEquals(2, requests.get())
    }

    @Test
    fun `an unconfigured server resolves to null without crashing`() = runBlocking {
        val store = DataStoreConnectionStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) {
                tempFolder.root.resolve("unconfigured_${System.nanoTime()}.preferences_pb")
            },
        )
        val manager = SpeakerManager(ConnectionManager(store, FakeTokenAccess()))

        assertNull(manager.nameFor("s1"))
    }

    @Test
    fun `a failed fetch resolves to null without raising an error`() = runBlocking {
        server.dispatch { jsonResponse("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""", code = 502) }
        val manager = SpeakerManager(trustedConnectionManager())

        assertNull(manager.nameFor("s1"))
    }

    @Test
    fun `concurrent lookups for the same miss coalesce into a single fetch`() = runBlocking {
        val requests = AtomicInteger(0)
        val requestReceived = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        server.dispatch {
            requests.incrementAndGet()
            requestReceived.countDown()
            releaseResponse.await(5, TimeUnit.SECONDS)
            jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
        }
        val manager = SpeakerManager(trustedConnectionManager())

        val callers = (1..3).map { async(Dispatchers.IO) { manager.nameFor("s1") } }
        requestReceived.await(5, TimeUnit.SECONDS)
        releaseResponse.countDown()
        val results = callers.awaitAll()

        assertEquals(listOf("Living Room", "Living Room", "Living Room"), results)
        assertEquals(1, requests.get())
    }

    @Test
    fun `refresh refetches even when every id is already cached`() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatch {
            requests.incrementAndGet()
            jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
        }
        val manager = SpeakerManager(trustedConnectionManager())
        manager.nameFor("s1") // populates the cache

        manager.refresh()

        assertEquals(2, requests.get())
    }

    @Test
    fun `refresh picks up a renamed speaker`() = runBlocking {
        val renamed = AtomicInteger(0)
        server.dispatch {
            if (renamed.getAndIncrement() == 0) {
                jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
            } else {
                jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Family Room")))
            }
        }
        val manager = SpeakerManager(trustedConnectionManager())
        assertEquals("Living Room", manager.nameFor("s1"))

        val result = manager.refresh()

        assertEquals("Family Room", (result as ApiResult.Success).data.single().name)
        assertEquals("Family Room", manager.nameFor("s1"))
    }

    @Test
    fun `refresh returns null when the server is unconfigured`() = runBlocking {
        val store = DataStoreConnectionStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) {
                tempFolder.root.resolve("unconfigured_${System.nanoTime()}.preferences_pb")
            },
        )
        val manager = SpeakerManager(ConnectionManager(store, FakeTokenAccess()))

        assertNull(manager.refresh())
    }

    @Test
    fun `a failed refresh returns the failure and leaves the cache untouched`() = runBlocking {
        val requests = AtomicInteger(0)
        server.dispatch {
            if (requests.getAndIncrement() == 0) {
                jsonResponse(WireFixtures.speakerListJson(WireFixtures.speakerJson(id = "s1", name = "Living Room")))
            } else {
                jsonResponse("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""", code = 502)
            }
        }
        val manager = SpeakerManager(trustedConnectionManager())
        manager.nameFor("s1") // populates the cache

        val result = manager.refresh()

        assertTrue(result is ApiResult.Failure)
        // Stale beats error: the previously cached name still resolves (and doesn't cost a
        // third request, proving the cache was left alone rather than cleared).
        assertEquals("Living Room", manager.nameFor("s1"))
        assertEquals(2, requests.get())
    }
}
