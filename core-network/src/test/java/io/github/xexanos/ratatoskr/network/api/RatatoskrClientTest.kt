/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

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
import org.junit.Assert.assertFalse
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
            .client(OkHttpClient())
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
    fun `401 maps to Unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","message":"no"}"""))

        val result = client.listSpeakers()

        assertEquals(RatatoskrError.Unauthorized, (result as ApiResult.Failure).error)
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
        assertTrue("request should carry the item id, was: $body", body.contains("\"i1\""))
        assertTrue("request should carry the speaker id, was: $body", body.contains("\"s1\""))
        // The /v2 request is item + speaker only; no refresh-token handoff leaks into it.
        assertFalse("request must not carry a token, was: $body", body.contains("token"))
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
