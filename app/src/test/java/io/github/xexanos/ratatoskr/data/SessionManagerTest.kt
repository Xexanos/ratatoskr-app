/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.xexanos.ratatoskr.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

class SessionManagerTest {

    @get:Rule val server = HttpsMockServer()

    @get:Rule val tempFolder = TemporaryFolder()

    // A real, single confined thread - not UnconfinedTestDispatcher (see NowPlayingViewModelTest,
    // whose command-vs-poll races this suite ports): control-action state writes must not run
    // inline on whichever OkHttp callback thread delivers the response.
    private val mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before fun setUp() = Dispatchers.setMain(mainThread)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThread.close()
    }

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun trustedConnectionManager(tokens: FakeTokenAccess = FakeTokenAccess()): ConnectionManager {
        val file = tempFolder.root.resolve("connection_${System.nanoTime()}.preferences_pb")
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { file }
        val store = DataStoreConnectionStore(dataStore)
        runBlocking { store.saveTrustedServer(server.baseUrl, server.fingerprint) }
        return ConnectionManager(store, tokens)
    }

    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMillis}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `poll populates the current session`() = runBlocking {
        server.dispatch { jsonResponse(WireFixtures.sessionJson(state = "playing")) }
        val connectionManager = trustedConnectionManager()
        val manager = SessionManager(connectionManager)

        manager.poll()

        val snapshot = manager.state.value
        assertEquals(PlaybackState.PLAYING, snapshot.session?.state)
        assertFalse(snapshot.loading)
        assertNull(snapshot.error)
        assertTrue(snapshot.active)
    }

    @Test
    fun `poll with no active session clears loading without an error`() = runBlocking {
        server.dispatch {
            jsonResponse("""{"code":"no_active_session","message":"Nothing playing"}""", code = 404)
        }
        val manager = SessionManager(trustedConnectionManager())

        manager.poll()

        val snapshot = manager.state.value
        assertNull(snapshot.session)
        assertFalse(snapshot.loading)
        assertNull(snapshot.error)
        assertFalse(snapshot.active)
    }

    @Test
    fun `a relinquished session clears the session, the stale error, and the active flag`() = runBlocking {
        val calls = AtomicInteger(0)
        server.dispatch {
            when (calls.getAndIncrement()) {
                0 -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
                1 -> jsonResponse("""{"code":"upstream_error","message":"Sonos is unavailable"}""", code = 502)
                else -> jsonResponse("""{"code":"no_active_session","message":"Nothing playing"}""", code = 404)
            }
        }
        val connectionManager = trustedConnectionManager()
        val manager = SessionManager(connectionManager)

        manager.poll() // playing -> session active
        assertTrue(manager.state.value.active)

        manager.poll() // 502 -> error kept, session and active flag retained (no error state per
        // consumer - this only reports the error, it does not clear the session)
        assertEquals("Sonos is unavailable", (manager.state.value.error as RatatoskrError.Upstream).message)
        assertEquals(PlaybackState.PLAYING, manager.state.value.session?.state)
        assertTrue(manager.state.value.active)

        manager.poll() // 404 -> relinquished: session gone, error cleared, active flag released
        val snapshot = manager.state.value
        assertNull(snapshot.session)
        assertNull(snapshot.error)
        assertFalse(snapshot.active)
    }

    @Test
    fun `poll failure surfaces the mapped error while keeping the last known session`() = runBlocking {
        server.dispatch { jsonResponse(WireFixtures.sessionJson(state = "playing")) }
        val manager = SessionManager(trustedConnectionManager())
        manager.poll()

        server.dispatch { jsonResponse("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""", code = 502) }
        manager.poll()

        val snapshot = manager.state.value
        assertEquals("Audiobookshelf down", (snapshot.error as RatatoskrError.Upstream).message)
        // Stale beats error: the last known session is still shown (mirrors the mini player's
        // no-error-state decision - a transient poll blip must not blank a live session).
        assertEquals(PlaybackState.PLAYING, snapshot.session?.state)
    }

    @Test
    fun `pause and resume update the session through the epoch guard`() = runBlocking {
        server.dispatch { request ->
            when {
                request.path.orEmpty().endsWith("/pause") -> jsonResponse(WireFixtures.sessionJson(state = "paused"))
                request.path.orEmpty().endsWith("/resume") -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
                else -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
            }
        }
        val manager = SessionManager(trustedConnectionManager())
        manager.poll()

        val pauseResult = manager.pause()
        assertEquals(PlaybackState.PAUSED, (pauseResult as ApiResult.Success).data.state)
        assertEquals(PlaybackState.PAUSED, manager.state.value.session?.state)

        val resumeResult = manager.resume()
        assertEquals(PlaybackState.PLAYING, (resumeResult as ApiResult.Success).data.state)
        assertEquals(PlaybackState.PLAYING, manager.state.value.session?.state)
    }

    @Test
    fun `stop clears the session and returns success to the caller`() = runBlocking {
        server.dispatch { request ->
            when {
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
            }
        }
        val connectionManager = trustedConnectionManager()
        val manager = SessionManager(connectionManager)
        manager.poll()

        val result = manager.stop()

        assertTrue(result is ApiResult.Success)
        assertNull(manager.state.value.session)
        assertFalse(manager.state.value.active)
    }

    @Test
    fun `a stale in-flight poll does not revive a session stop() already ended`() = runBlocking {
        val pollRequested = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        server.dispatch { request ->
            when {
                request.method == "GET" -> {
                    pollRequested.countDown()
                    // Simulate a poll that was already in flight when the user stopped: it only
                    // resolves (with the now-stale "playing" state) after stop() has landed.
                    releasePoll.await(5, TimeUnit.SECONDS)
                    jsonResponse(WireFixtures.sessionJson(state = "playing"))
                }
                request.method == "DELETE" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val manager = SessionManager(trustedConnectionManager())

        val pollJob = launch(Dispatchers.IO) { manager.poll() }
        assertTrue("poll never reached the server", pollRequested.await(5, TimeUnit.SECONDS))

        val stopResult = manager.stop()
        assertTrue(stopResult is ApiResult.Success)
        assertNull(manager.state.value.session)

        releasePoll.countDown()
        pollJob.join()

        // The poll's stale "playing" result must not revive the session stop() already ended
        // (the commandEpoch guard, ported from NowPlayingViewModel).
        assertNull(manager.state.value.session)
    }

    @Test
    fun `a control action superseded before it lands returns null instead of a stale failure`() = runBlocking {
        val pauseRequested = CountDownLatch(1)
        val releasePauseFailure = CountDownLatch(1)
        server.dispatch { request ->
            when {
                request.path.orEmpty().endsWith("/pause") -> {
                    pauseRequested.countDown()
                    releasePauseFailure.await(5, TimeUnit.SECONDS)
                    MockResponse().setResponseCode(502)
                        .setBody("""{"code":"upstream_error","message":"Sonos is unavailable"}""")
                }
                request.path.orEmpty().endsWith("/resume") -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
                else -> jsonResponse(WireFixtures.sessionJson(state = "paused"))
            }
        }
        val manager = SessionManager(trustedConnectionManager())
        manager.poll() // paused, so pause() below is a plausible (if pointless) action to race

        val pauseDeferred = async(Dispatchers.IO) { manager.pause() }
        assertTrue("pause never reached the server", pauseRequested.await(5, TimeUnit.SECONDS))

        val resumeResult = manager.resume()
        assertTrue(resumeResult is ApiResult.Success)

        releasePauseFailure.countDown()
        val pauseResult = pauseDeferred.await()

        // resume() superseded the in-flight pause(): its stale failure is suppressed (null)
        // rather than surfaced to the caller, and the session stays "playing".
        assertNull(pauseResult)
        assertEquals(PlaybackState.PLAYING, manager.state.value.session?.state)
    }

    @Test
    fun `a 401 while a session is active signals reauth and keeps the username for the pre-fill`() = runBlocking {
        // The "Anmeldung abgelaufen" recovery (SPEC section 5): an audiobook is playing (session
        // active), the app is backgrounded, and on the next poll the stored Ratatoskr token is no
        // longer accepted - it was revoked, or the server's upstream session died. There is no
        // refresh to fall back on, so the app clears the stranded token and asks the user to sign
        // in again, keeping the trusted server, its certificate, and the username.
        val calls = AtomicInteger(0)
        server.dispatch {
            when (calls.getAndIncrement()) {
                0 -> jsonResponse(WireFixtures.sessionJson(state = "playing"))
                else -> jsonResponse("""{"code":"UPSTREAM_SESSION_LOST","message":"token no longer valid"}""", code = 401)
            }
        }
        val connectionManager =
            trustedConnectionManager(FakeTokenAccess("stale-token", AuthUser("7", "alex")))
        val manager = SessionManager(connectionManager)

        manager.poll() // playing -> session active
        assertTrue(manager.state.value.active)

        manager.poll() // 401 after a long background: the stored token is dead
        assertEquals(RatatoskrError.Unauthorized("UPSTREAM_SESSION_LOST"), manager.state.value.error)
        // Recovery (SPEC section 5): ask the user to sign in again. The stranded token is cleared
        // and a re-auth is signalled, which the nav host routes to the sign-in screen. The username
        // survives for the pre-fill, and the 401 code is forwarded so the screen can vary its notice.
        assertTrue(connectionManager.reauthRequired.value)
        assertNull(connectionManager.tokenStore.authSession())
        assertEquals("alex", connectionManager.prefillUsername())
        assertEquals(SignInPrompt.Reauth("UPSTREAM_SESSION_LOST"), connectionManager.consumeSignInPrompt())
    }

    @Test
    fun `a 401 while signed in but not playing still forces reauth`() = runBlocking {
        // SPEC section 5: every 401 while signed in leads to the same recovery, not only during
        // playback. The user holds a token but nothing is playing (browsing the library, say) when
        // the token is revoked; the poll's 401 must still route them to a pre-filled sign-in.
        server.dispatch { jsonResponse("""{"code":"unauthorized","message":"token revoked"}""", code = 401) }
        val connectionManager =
            trustedConnectionManager(FakeTokenAccess("live-token", AuthUser("7", "alex")))
        val manager = SessionManager(connectionManager)

        manager.poll() // 401 while signed in, no active session

        assertEquals(RatatoskrError.Unauthorized("unauthorized"), manager.state.value.error)
        assertFalse(manager.state.value.active)
        assertTrue(connectionManager.reauthRequired.value)
        assertNull(connectionManager.tokenStore.authSession())
        assertEquals("alex", connectionManager.prefillUsername())
    }

    @Test
    fun `a 401 poll during the unauthenticated window does not force reauth`() = runBlocking {
        // Issue #108: the process-wide poll runs during the unauthenticated window too - a server
        // is trusted so client() is non-null, but no one is signed in (the user is on
        // Connect/Sign-in). No token was ever stored, so its 401 is the ordinary "not signed in"
        // state, not a dead credential: it must NOT escalate to reauth (see maybeRequireReauth's
        // hadCredential guard).
        server.dispatch { jsonResponse("""{"code":"unauthorized","message":"no token"}""", code = 401) }
        val connectionManager = trustedConnectionManager(FakeTokenAccess()) // no credential stored
        val manager = SessionManager(connectionManager)

        manager.poll() // 401 while signed out

        assertEquals(RatatoskrError.Unauthorized("unauthorized"), manager.state.value.error)
        assertFalse(connectionManager.reauthRequired.value)
    }

    @Test
    fun `a 401 for a poll begun before sign-in does not clear the just-stored token`() = runBlocking {
        // The post-login race the hadCredential guard exists for: a poll issues its request during
        // the unauthenticated window (no token), the user signs in, and only then does that
        // pre-sign-in poll's 401 arrive. Escalating it would clear the freshly stored token and
        // bounce the user off the Library. The credential snapshot is taken when the poll began, so
        // this stale 401 is ignored.
        val requestReached = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatch {
            requestReached.countDown() // the poll has already read hadCredential (= false) by now
            release.await()
            jsonResponse("""{"code":"unauthorized","message":"no token was sent"}""", code = 401)
        }
        val tokens = FakeTokenAccess() // unauthenticated window: no token yet
        val connectionManager = trustedConnectionManager(tokens)
        val manager = SessionManager(connectionManager)

        val poll = async(Dispatchers.IO) { manager.poll() }
        requestReached.await(5, TimeUnit.SECONDS)
        tokens.save(AuthSession("fresh-token", AuthUser("7", "alex"))) // sign in mid-flight
        release.countDown()
        poll.await()

        // The 401 belonged to the token-less request, so the fresh token survives and no reauth fires.
        assertFalse(connectionManager.reauthRequired.value)
        assertNotNull(connectionManager.tokenStore.authSession())
    }
}
