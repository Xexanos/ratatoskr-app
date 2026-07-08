/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.component

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SPEC section 9, bullet (c): bearer attachment and the 401 -> refresh -> retry flow through
 * the fully assembled client. Unlike the JVM `TokenRefreshAuthenticatorTest`, this drives the
 * factory's REAL refresher, which posts to `/v1/auth/refresh` on the no-auth client - so the
 * whole bearer + authenticator + refresh-endpoint wiring is exercised end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class FactoryAuthComponentTest {

    @get:Rule val https = HttpsMockServer()

    private fun client(
        tokens: FakeTokenAccess = FakeTokenAccess("a0", "r0"),
        sessionActive: () -> Boolean = { false },
    ): RatatoskrClient =
        https.track(RatatoskrClientFactory.create(https.baseUrl, https.fingerprint, tokens, sessionActive))

    private val freshTokens = WireFixtures.authTokensJson(accessToken = "a1", refreshToken = "r1")

    @Test
    fun theBearerTokenIsAttachedToRequests() = runBlocking {
        https.enqueueJson("[]")

        client().listSpeakers()

        val request = https.takeRequest()
        assertEquals("Bearer a0", request.getHeader("Authorization"))
    }

    @Test
    fun a401TriggersRefreshViaTheRefreshEndpointAndTheRetrySucceeds() = runBlocking {
        val refreshCalls = AtomicInteger()
        https.dispatch { request ->
            when {
                request.path?.contains("/auth/refresh") == true -> {
                    refreshCalls.incrementAndGet()
                    MockResponse().setBody(freshTokens)
                }
                request.getHeader("Authorization") == "Bearer a1" -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(401)
            }
        }
        val tokens = FakeTokenAccess("a0", "r0")

        val result = client(tokens).listSpeakers()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals(1, refreshCalls.get())
        assertEquals("a1", tokens.currentAccessTokenBlocking())
    }

    @Test
    fun concurrent401sTriggerExactlyOneRefresh() {
        val refreshCalls = AtomicInteger()
        https.dispatch { request ->
            when {
                request.path?.contains("/auth/refresh") == true -> {
                    refreshCalls.incrementAndGet()
                    Thread.sleep(150) // widen the race window
                    MockResponse().setBody(freshTokens)
                }
                request.getHeader("Authorization") == "Bearer a1" -> MockResponse().setBody("[]")
                else -> MockResponse().setResponseCode(401)
            }
        }
        val client = client(FakeTokenAccess("a0", "r0"))

        val n = 5
        val ready = CountDownLatch(n)
        val done = CountDownLatch(n)
        val outcomes = mutableListOf<Boolean>()
        val pool = Executors.newFixedThreadPool(n)
        repeat(n) {
            pool.execute {
                ready.countDown(); ready.await()
                val r = runBlocking { client.listSpeakers() }
                synchronized(outcomes) { outcomes += (r is ApiResult.Success) }
                done.countDown()
            }
        }
        // Bounded wait: if the refresh path ever deadlocks again (e.g. a shared dispatcher
        // starves the refresh under concurrent 401s), fail here in seconds instead of hanging
        // until the CI job timeout.
        val finished = done.await(30, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertTrue("requests deadlocked: only ${outcomes.size}/$n finished", finished)
        assertEquals(1, refreshCalls.get())
        assertTrue("all requests should succeed, was $outcomes", outcomes.all { it })
    }

    @Test
    fun refreshIsSuppressedWhileASessionIsActiveSoThe401Surfaces() = runBlocking {
        val refreshCalls = AtomicInteger()
        https.dispatch { request ->
            if (request.path?.contains("/auth/refresh") == true) {
                refreshCalls.incrementAndGet()
                MockResponse().setBody(freshTokens)
            } else {
                MockResponse().setResponseCode(401)
            }
        }

        val result = client(FakeTokenAccess("a0", "r0"), sessionActive = { true }).listSpeakers()

        assertEquals(RatatoskrError.Unauthorized, (result as ApiResult.Failure).error)
        assertEquals(0, refreshCalls.get())
    }
}
