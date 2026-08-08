/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.generated.api.LibraryApi
import io.github.xexanos.ratatoskr.network.generated.api.PlaybackApi
import io.github.xexanos.ratatoskr.network.generated.api.SpeakersApi
import io.github.xexanos.ratatoskr.network.generated.api.SystemApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

class RatatoskrClientTest {

    private val server = MockWebServer()
    private val tokens = FakeTokenAccess(token = "t0")
    private lateinit var client: RatatoskrClient

    @Before
    fun setUp() {
        server.start()
        val moshi = ratatoskrMoshi()
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/v2/"))
            // Production-shaped auth chain (the factory wires the same interceptor), so the
            // signOut test can assert the bearer actually rides on the logout request.
            .client(OkHttpClient.Builder().addInterceptor(BearerAuthInterceptor(tokens)).build())
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        client = RatatoskrClient(
            systemApi = retrofit.create(SystemApi::class.java),
            speakersApi = retrofit.create(SpeakersApi::class.java),
            libraryApi = retrofit.create(LibraryApi::class.java),
            playbackApi = retrofit.create(PlaybackApi::class.java),
            tokenStore = tokens,
            moshi = moshi,
            coverEndpoint = CoverEndpoint(server.url("/").toString()),
            coversCallFactory = OkHttpClient(),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `login stores the returned Ratatoskr token`() = runBlocking {
        server.enqueue(MockResponse().setBody(WireFixtures.authSessionJson()))

        val result = client.login("alex", "secret")

        assertTrue(result is ApiResult.Success)
        assertEquals("t1", tokens.currentTokenBlocking())
        assertEquals("alex", tokens.savedSession!!.user.username)
    }

    @Test
    fun `404 on a session endpoint maps to NoActiveSession`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"code":"no_active_session","message":"Nothing playing"}"""),
        )

        val result = client.currentSession()

        val error = (result as ApiResult.Failure).error
        assertEquals(RatatoskrError.NoActiveSession, error)
    }

    @Test
    fun `404 elsewhere maps to NotFound`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"nf","message":"gone"}"""))

        val result = client.getLibraryItem("nope")

        assertEquals(RatatoskrError.NotFound, (result as ApiResult.Failure).error)
    }

    @Test
    fun `bare 404 on a session endpoint maps to ServerTooOld`() = runBlocking {
        // A server too old to speak /v2 answers its routes with a bare 404 - no contract error
        // body - unlike a real /v2 404, which always carries one (SPEC section 5, rollout guard).
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.currentSession()

        assertEquals(RatatoskrError.ServerTooOld, (result as ApiResult.Failure).error)
    }

    @Test
    fun `bare 404 elsewhere maps to ServerTooOld`() = runBlocking {
        // "Bare" includes a framework's HTML error page: anything but the contract's Error shape.
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("<html>Cannot POST /v2/auth/login</html>"),
        )

        val result = client.login("alex", "secret")

        assertEquals(RatatoskrError.ServerTooOld, (result as ApiResult.Failure).error)
    }

    @Test
    fun `401 maps to Unauthorized carrying the body code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","message":"no"}"""))

        val result = client.listSpeakers()

        assertEquals(RatatoskrError.Unauthorized("unauthorized"), (result as ApiResult.Failure).error)
    }

    @Test
    fun `401 keeps UPSTREAM_SESSION_LOST so the sign-in notice can vary`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"UPSTREAM_SESSION_LOST","message":"gone"}"""),
        )

        val result = client.listSpeakers()

        assertEquals(RatatoskrError.Unauthorized("UPSTREAM_SESSION_LOST"), (result as ApiResult.Failure).error)
    }

    @Test
    fun `a 401 with no body code maps to Unauthorized with a null code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.listSpeakers()

        assertEquals(RatatoskrError.Unauthorized(null), (result as ApiResult.Failure).error)
    }

    @Test
    fun `502 maps to Upstream with the parsed error body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setBody("""{"code":"abs_unreachable","message":"Audiobookshelf down"}"""),
        )

        val result = client.listSpeakers()

        val error = (result as ApiResult.Failure).error as RatatoskrError.Upstream
        assertEquals("abs_unreachable", error.code)
        assertEquals("Audiobookshelf down", error.message)
    }

    @Test
    fun `startSession posts only the item and speaker, no token handoff`() = runBlocking {
        server.enqueue(MockResponse().setBody(WireFixtures.sessionJson(positionSeconds = 0.0)))

        val result = client.startSession("i1", "s1")

        assertTrue(result is ApiResult.Success)
        val body = server.takeRequest().body.readUtf8()
        val fields = Moshi.Builder().build()
            .adapter<Map<String, Any?>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
            )
            .fromJson(body)
        assertEquals(mapOf("itemId" to "i1", "speakerId" to "s1"), fields)
    }

    @Test
    fun `cancellation propagates instead of becoming a Failure`() = runBlocking {
        // No response is enqueued, so the call hangs until the coroutine is cancelled. If the
        // wrapper swallowed CancellationException it would complete normally with a Failure;
        // instead the job must end cancelled.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            client.currentSession()
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `signOut posts logout with the bearer token and clears the store`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        client.signOut()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/auth/logout", request.path)
        // Asserted explicitly because MockWebServer enforces no auth (see #126).
        assertEquals("Bearer t0", request.getHeader("Authorization"))
        assertNull(tokens.currentTokenBlocking())
    }

    @Test
    fun `signOut clears the store even when the server rejects the logout`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"code":"boom","message":"broken"}"""),
        )

        client.signOut()

        assertNull(tokens.currentTokenBlocking())
    }

    @Test
    fun `signOut clears the store even when the server is unreachable`() = runBlocking {
        server.shutdown()

        client.signOut()

        assertNull(tokens.currentTokenBlocking())
    }

    @Test
    fun `stopSession succeeds on a 204 and keeps the stored token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.stopSession()

        assertTrue(result is ApiResult.Success)
        assertEquals("t0", tokens.currentTokenBlocking())
    }

    @Test
    fun `an unrecognised playback state falls back instead of failing the response`() = runBlocking {
        // A newer server could report a state this app's enum doesn't know; the response must
        // still deserialize rather than surfacing as an error (SPEC section 4).
        server.enqueue(MockResponse().setBody(WireFixtures.sessionJson(state = "warping")))

        val result = client.currentSession()

        assertTrue(result is ApiResult.Success)
        assertEquals(PlaybackState.STOPPED, (result as ApiResult.Success).data.state)
    }
}
