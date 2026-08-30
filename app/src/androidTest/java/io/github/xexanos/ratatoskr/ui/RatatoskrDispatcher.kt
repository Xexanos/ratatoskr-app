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
 * probes, plus the `/v2/` endpoints, and tracks playback `state`/`position` so pause/resume/
 * seek produce a realistic session on the next poll.
 *
 * Response bodies come from the shared [WireFixtures]; [login] is injectable so a test can make
 * sign-in fail.
 */
class RatatoskrDispatcher(
    private val login: () -> MockResponse = { jsonResponse(WireFixtures.authSessionJson()) },
    private val speakers: String = WireFixtures.speakerListJson(),
    private val libraryPage: String = WireFixtures.libraryPageJson(),
    // Empty by default: the flows' row counts and "first row" taps predate the shelf, and an
    // empty shelf renders the screen exactly as before it existed.
    private val inProgressShelf: String = WireFixtures.inProgressShelfJson(items = emptyList()),
    private val sessionDurationSeconds: Double = 600.0,
    /** Optional embedded `LibraryItemSummary` JSON for the session's `item` member. */
    private val sessionItemJson: String? = null,
) : Dispatcher() {

    @Volatile private var state = "playing"
    // Low start position against a long duration (see session()) so the seek test can move the
    // slider to a distinct value; a maxed-out slider would clamp SetProgress to a no-op.
    @Volatile private var position = 30.0
    // Once stopped (DELETE), a real server reports no active session; mirror that so a stray
    // poll after Stop gets 404, not a revived session.
    @Volatile private var ended = false

    // When set, every authenticated request answers 401 with this code, standing in for a dead
    // Ratatoskr token (SPEC section 5). A fresh /v2/auth/login clears it - re-login mints a working
    // token. Used to drive the 401 re-authentication flow.
    @Volatile private var unauthorizedCode: String? = null

    /** Simulate the stored token dying: the next authenticated request 401s with [code]. */
    fun expireTokenWith(code: String) { unauthorizedCode = code }

    /** The last request the cover route served, for asserting auth and the `?h=` bucket. */
    @Volatile var lastCoverRequest: RecordedRequest? = null
        private set

    /** The last logout request served, for asserting sign-out told the server. */
    @Volatile var lastLogoutRequest: RecordedRequest? = null
        private set

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        // A dead token 401s every authenticated route (not /health, not login itself, which mints a
        // fresh one and clears the flag). Checked before the route table so any polled/tapped call
        // triggers the re-auth path (SPEC section 5).
        if (unauthorizedCode != null && path != "/health" && path != "/v2/auth/login") {
            return jsonResponse("""{"code":"$unauthorizedCode","message":"token no longer valid"}""", code = 401)
        }
        return when {
            path == "/health" -> jsonResponse("""{"reachable":true}""")
            path == "/v2/auth/login" -> { unauthorizedCode = null; login() }
            path == "/v2/auth/logout" -> {
                lastLogoutRequest = request
                MockResponse().setResponseCode(204)
            }
            path == "/v2/speakers" -> jsonResponse(speakers)
            // The cover proxy: real PNG bytes so Coil decodes and renders them.
            path.startsWith("/v2/library/items/") && path.endsWith("/cover") -> {
                lastCoverRequest = request
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(COVER_PNG))
            }
            // A library item tap navigates to the speaker picker, so the item-detail endpoint
            // is not part of the happy path; answer defensively.
            path.startsWith("/v2/library/items/") -> MockResponse().setResponseCode(404)
            path == "/v2/library/items" -> jsonResponse(libraryPage)
            path == "/v2/library/in-progress" -> jsonResponse(inProgressShelf)
            path == "/v2/sessions/current" && request.method == "PUT" -> {
                ended = false
                state = "playing"
                jsonResponse(session())
            }
            path == "/v2/sessions/current" && request.method == "GET" ->
                if (ended) {
                    jsonResponse("""{"code":"no_active_session","message":"Nothing playing"}""", code = 404)
                } else {
                    jsonResponse(session())
                }
            path == "/v2/sessions/current" && request.method == "DELETE" -> {
                ended = true
                MockResponse().setResponseCode(204)
            }
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

    private fun session() =
        WireFixtures.sessionJson(
            state = state,
            positionSeconds = position,
            durationSeconds = sessionDurationSeconds,
            extraJson = sessionItemJson?.let { """"item":$it""" } ?: "",
        )

    private companion object {
        val POSITION = Regex(""""positionSeconds"\s*:\s*([0-9.]+)""")

        /** A valid 1x1 PNG - the smallest body Coil can actually decode. */
        val COVER_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGM4UWHzHwAGBAJ87l7R9AAAAABJRU5ErkJggg==",
        )
    }
}

private fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
