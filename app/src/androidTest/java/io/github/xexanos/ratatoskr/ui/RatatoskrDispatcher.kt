/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import io.github.xexanos.ratatoskr.network.WireFixtures
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * A stateful MockWebServer dispatcher standing in for the Ratatoskr server across a whole-app
 * flow. Path + method based (not enqueue) because a UI flow issues many requests in an order
 * the test does not control (the now-playing screen also polls). It serves the root
 * `GET /health` the connect screen's [io.github.xexanos.ratatoskr.network.tls.CertificateInspector]
 * probes, plus the `/v1/` endpoints, and tracks playback `state`/`position` so pause/resume/
 * seek produce a realistic session on the next poll.
 *
 * Response bodies come from the shared [WireFixtures]; [login] is injectable so a test can make
 * sign-in fail.
 */
class RatatoskrDispatcher(
    private val login: () -> MockResponse = { jsonResponse(WireFixtures.authTokensJson()) },
    private val speakers: String = WireFixtures.speakerListJson(),
    private val libraryPage: String = WireFixtures.libraryPageJson(),
) : Dispatcher() {

    @Volatile private var state = "playing"
    @Volatile private var position = 10.0

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        return when {
            path == "/health" -> jsonResponse("""{"reachable":true}""")
            path == "/v1/auth/login" -> login()
            path == "/v1/speakers" -> jsonResponse(speakers)
            // A library item tap navigates to the speaker picker, so the item-detail endpoint
            // is not part of the happy path; answer defensively.
            path.startsWith("/v1/library/items/") -> MockResponse().setResponseCode(404)
            path == "/v1/library/items" -> jsonResponse(libraryPage)
            path == "/v1/sessions/current" && request.method == "PUT" -> {
                state = "playing"
                jsonResponse(session())
            }
            path == "/v1/sessions/current" && request.method == "GET" -> jsonResponse(session())
            path == "/v1/sessions/current" && request.method == "DELETE" -> MockResponse().setResponseCode(204)
            path.endsWith("/pause") -> { state = "paused"; jsonResponse(session()) }
            path.endsWith("/resume") -> { state = "playing"; jsonResponse(session()) }
            path.endsWith("/seek") -> {
                POSITION.find(request.body.readUtf8())?.groupValues?.get(1)?.toDoubleOrNull()
                    ?.let { position = it }
                jsonResponse(session())
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun session() = WireFixtures.sessionJson(state = state, positionSeconds = position)

    private companion object {
        val POSITION = Regex(""""positionSeconds"\s*:\s*([0-9.]+)""")
    }
}

private fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
