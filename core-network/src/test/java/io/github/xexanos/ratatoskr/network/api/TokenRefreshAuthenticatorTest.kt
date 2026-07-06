/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TokenRefreshAuthenticatorTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    /** 401 for anything but the fresh token; 200 once the fresh token is presented. */
    private fun requireToken(valid: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer $valid") MockResponse().setBody("ok")
                else MockResponse().setResponseCode(401)
        }
    }

    private fun renewed(access: String) =
        AuthSession(access, "refresh-next", AuthUser("1", "lars"))

    private fun client(
        tokens: FakeTokenAccess,
        refresher: TokenRefresher,
        suppressed: () -> Boolean = { false },
    ) = OkHttpClient.Builder()
        .addInterceptor(BearerAuthInterceptor(tokens))
        .authenticator(TokenRefreshAuthenticator(tokens, refresher, suppressed))
        .build()

    @Test
    fun `refreshes once on 401 and retries with the new token`() {
        requireToken("fresh")
        val tokens = FakeTokenAccess(accessToken = "stale", refreshToken = "refresh-1")
        val refreshCalls = AtomicInteger()
        val refresher = TokenRefresher { rt ->
            refreshCalls.incrementAndGet()
            assertEquals("refresh-1", rt)
            renewed("fresh")
        }

        val response = client(tokens, refresher)
            .newCall(Request.Builder().url(server.url("/v1/speakers")).build())
            .execute()

        assertEquals(200, response.code)
        assertEquals(1, refreshCalls.get())
        assertEquals("fresh", tokens.currentAccessTokenBlocking())
        response.close()
    }

    @Test
    fun `single-flight - concurrent 401s trigger exactly one refresh`() {
        requireToken("fresh")
        val tokens = FakeTokenAccess(accessToken = "stale", refreshToken = "refresh-1")
        val refreshCalls = AtomicInteger()
        val refresher = TokenRefresher {
            refreshCalls.incrementAndGet()
            Thread.sleep(150)   // widen the race window
            renewed("fresh")
        }
        val http = client(tokens, refresher)

        val n = 5
        val ready = CountDownLatch(n)
        val done = CountDownLatch(n)
        val codes = mutableListOf<Int>()
        val pool = Executors.newFixedThreadPool(n)
        repeat(n) {
            pool.execute {
                ready.countDown(); ready.await()
                val r = http.newCall(Request.Builder().url(server.url("/v1/speakers")).build()).execute()
                synchronized(codes) { codes += r.code }
                r.close()
                done.countDown()
            }
        }
        done.await()
        pool.shutdown()

        assertEquals(1, refreshCalls.get())
        assertTrue("all requests should succeed, got $codes", codes.all { it == 200 })
    }

    @Test
    fun `gives up after one retry instead of looping`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val tokens = FakeTokenAccess(accessToken = "stale", refreshToken = "refresh-1")
        val refreshCalls = AtomicInteger()
        val refresher = TokenRefresher { refreshCalls.incrementAndGet(); renewed("still-rejected") }

        val response = client(tokens, refresher)
            .newCall(Request.Builder().url(server.url("/v1/speakers")).build())
            .execute()

        assertEquals(401, response.code)
        assertEquals(1, refreshCalls.get())
        response.close()
    }

    @Test
    fun `does not refresh while a playback session is active`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val tokens = FakeTokenAccess(accessToken = "stale", refreshToken = "refresh-1")
        val refreshCalls = AtomicInteger()
        val refresher = TokenRefresher { refreshCalls.incrementAndGet(); renewed("fresh") }

        val response = client(tokens, refresher, suppressed = { true })
            .newCall(Request.Builder().url(server.url("/v1/speakers")).build())
            .execute()

        assertEquals(401, response.code)
        assertEquals(0, refreshCalls.get())
        assertEquals("stale", tokens.currentAccessTokenBlocking())
        response.close()
    }

    @Test
    fun `failed refresh surfaces the 401`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val tokens = FakeTokenAccess(accessToken = "stale", refreshToken = "refresh-1")
        val refresher = TokenRefresher { null }

        val response = client(tokens, refresher)
            .newCall(Request.Builder().url(server.url("/v1/speakers")).build())
            .execute()

        assertEquals(401, response.code)
        response.close()
    }
}
