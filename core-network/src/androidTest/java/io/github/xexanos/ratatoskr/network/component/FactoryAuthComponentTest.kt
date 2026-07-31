/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.component

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * SPEC section 9, bullet (c): bearer attachment and 401 handling through the fully assembled
 * client. On /v2 the Ratatoskr token is opaque and non-expiring, so there is no refresh path:
 * the bearer interceptor attaches the stored token and a 401 is surfaced verbatim, never
 * silently retried.
 */
@RunWith(AndroidJUnit4::class)
class FactoryAuthComponentTest {

    @get:Rule val https = HttpsMockServer()

    private fun client(tokens: FakeTokenAccess = FakeTokenAccess(token = "t0")): RatatoskrClient =
        https.track(RatatoskrClientFactory.create(https.baseUrl, https.fingerprint, tokens))

    @Test
    fun theBearerTokenIsAttachedToRequests() = runBlocking {
        https.enqueueJson("[]")

        client().listSpeakers()

        val request = https.takeRequest()
        assertEquals("Bearer t0", request.getHeader("Authorization"))
    }

    @Test
    fun a401SurfacesAsUnauthorizedWithoutRetrying() = runBlocking {
        val requests = AtomicInteger()
        https.dispatch {
            requests.incrementAndGet()
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"unauthorized","message":"no"}""")
        }

        val result = client().listSpeakers()

        assertEquals(RatatoskrError.Unauthorized, (result as ApiResult.Failure).error)
        // No authenticator means no silent retry: the single request is the whole story.
        assertEquals(1, requests.get())
    }
}
