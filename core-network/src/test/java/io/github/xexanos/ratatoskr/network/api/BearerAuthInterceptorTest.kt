/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BearerAuthInterceptorTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun client(tokens: FakeTokenAccess) =
        OkHttpClient.Builder().addInterceptor(BearerAuthInterceptor(tokens)).build()

    @Test
    fun `attaches bearer token to regular requests`() {
        server.enqueue(MockResponse())
        client(FakeTokenAccess(accessToken = "token-1"))
            .newCall(Request.Builder().url(server.url("/v1/speakers")).build())
            .execute().close()

        assertEquals("Bearer token-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `does not attach a token to auth endpoints`() {
        server.enqueue(MockResponse())
        client(FakeTokenAccess(accessToken = "token-1"))
            .newCall(Request.Builder().url(server.url("/v1/auth/login")).build())
            .execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `proceeds without a header when no token is stored`() {
        server.enqueue(MockResponse())
        client(FakeTokenAccess())
            .newCall(Request.Builder().url(server.url("/v1/speakers")).build())
            .execute().close()

        assertNull(server.takeRequest().getHeader("Authorization"))
    }
}
