/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 9, bullet (e): HTTP and transport failures mapped to the right [RatatoskrError]
 * through the assembled client, including the error body being parsed by the factory's own
 * Moshi (`Upstream` carries the parsed code/message).
 */
@RunWith(AndroidJUnit4::class)
class FactoryErrorMappingIntegrationTest {

    private val https = HttpsMockServer()
    private val created = mutableListOf<RatatoskrClient>()

    @Before fun setUp() = https.start()

    @After fun tearDown() {
        created.forEach { it.close() }
        https.shutdown()
    }

    private fun client(
        fingerprint: String? = https.fingerprint,
        sessionActive: () -> Boolean = { false },
    ): RatatoskrClient =
        RatatoskrClientFactory.create(https.baseUrl, fingerprint, FakeTokenAccess("a0", "r0"), sessionActive)
            .also { created += it }

    @Test
    fun a401MapsToUnauthorized() = runBlocking {
        https.enqueueJson("""{"code":"unauthorized","message":"no"}""", code = 401)

        // sessionActive suppresses the refresh attempt so the 401 surfaces as the mapped error.
        val result = client(sessionActive = { true }).listSpeakers()

        assertEquals(RatatoskrError.Unauthorized, (result as ApiResult.Failure).error)
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
