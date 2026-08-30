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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 9, bullet (e): HTTP and transport failures mapped to the right [RatatoskrError]
 * through the assembled client, including the error body being parsed by the factory's own
 * Moshi (`Upstream` carries the parsed code/message).
 */
@RunWith(AndroidJUnit4::class)
class FactoryErrorMappingComponentTest {

    @get:Rule val https = HttpsMockServer()

    private fun client(fingerprint: String? = https.fingerprint): RatatoskrClient =
        https.track(
            RatatoskrClientFactory.create(https.baseUrl, fingerprint, FakeTokenAccess(token = "t0")),
        )

    @Test
    fun a401MapsToUnauthorized() = runBlocking {
        https.enqueueJson("""{"code":"unauthorized","message":"no"}""", code = 401)

        // No refresh path on /v2: the 401 surfaces verbatim as the mapped error.
        val result = client().listSpeakers()

        assertEquals(RatatoskrError.Unauthorized("unauthorized"), (result as ApiResult.Failure).error)
    }

    @Test
    fun a404OnASessionEndpointMapsToNoActiveSession() = runBlocking {
        https.enqueueJson("""{"code":"no_active_session","message":"Nothing playing"}""", code = 404)

        val result = client().currentSession()

        assertEquals(RatatoskrError.NoActiveSession, (result as ApiResult.Failure).error)
    }

    @Test
    fun a404ElsewhereMapsToNotFound() = runBlocking {
        https.enqueueJson("""{"code":"nf","message":"gone"}""", code = 404)

        val result = client().getLibraryItem("nope")

        assertEquals(RatatoskrError.NotFound, (result as ApiResult.Failure).error)
    }

    @Test
    fun aBare404MapsToServerTooOld() = runBlocking {
        https.server.enqueue(MockResponse().setResponseCode(404))

        val result = client().getLibraryItem("nope")

        assertEquals(RatatoskrError.ServerTooOld, (result as ApiResult.Failure).error)
    }

    @Test
    fun a502MapsToUpstreamWithTheParsedErrorBody() = runBlocking {
        https.enqueueJson("""{"code":"abs_unreachable","message":"Audiobookshelf down"}""", code = 502)

        val result = client().listSpeakers()

        val error = (result as ApiResult.Failure).error as RatatoskrError.Upstream
        assertEquals("abs_unreachable", error.code)
        assertEquals("Audiobookshelf down", error.message)
    }

    @Test
    fun anotherStatusMapsToServer() = runBlocking {
        https.enqueueJson("""{"code":"boom","message":"internal"}""", code = 500)

        val result = client().listSpeakers()

        val error = (result as ApiResult.Failure).error as RatatoskrError.Server
        assertEquals(500, error.httpStatus)
    }

    @Test
    fun aTlsFailureMapsToCertificateUntrusted() = runBlocking {
        val result = client(fingerprint = https.wrongFingerprint).listSpeakers()

        val error = (result as ApiResult.Failure).error
        assertTrue("expected CertificateUntrusted, was $error", error is RatatoskrError.CertificateUntrusted)
    }
}
