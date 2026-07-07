/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network

/**
 * Canonical wire-format response bodies for the contract shapes the tests exercise, shared
 * by the JVM unit tests and the instrumented integration tests. One definition per shape:
 * when the contract changes, this file changes - not a dozen inline JSON literals across
 * two source sets, where a missed copy keeps a test green against an outdated shape.
 */
object WireFixtures {

    /**
     * A `Session` response body. [rotatedTokens] adds the optional server-rotated pair
     * (SPEC section 5); [extraJson] injects raw additional members (e.g.
     * `"someFutureField":123`) for unknown-field-tolerance tests.
     */
    fun sessionJson(
        state: String = "playing",
        positionSeconds: Double = 1.0,
        rotatedTokens: Pair<String, String>? = null,
        extraJson: String = "",
    ): String {
        val rotated = rotatedTokens?.let { (access, refresh) ->
            ""","rotatedTokens":{"accessToken":"$access","refreshToken":"$refresh"}"""
        } ?: ""
        val extra = if (extraJson.isEmpty()) "" else ",$extraJson"
        return """{"itemId":"i1","speakerId":"s1","state":"$state","positionSeconds":$positionSeconds,""" +
            """"durationSeconds":10.0,"updatedAt":"2026-07-05T12:00:00Z"$rotated$extra}"""
    }

    /** An `AuthTokens` response body, as returned by login and refresh. */
    fun authTokensJson(
        accessToken: String = "a1",
        refreshToken: String = "r1",
        username: String = "lars",
    ): String =
        """{"accessToken":"$accessToken","refreshToken":"$refreshToken",""" +
            """"user":{"id":"7","username":"$username"}}"""
}
