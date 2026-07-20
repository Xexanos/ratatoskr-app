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
 *
 * String values are JSON-escaped (see [esc]) so a caller passing a title/name with a quote or
 * backslash still produces valid JSON instead of a body that fails to deserialize far away.
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
        durationSeconds: Double = 10.0,
        rotatedTokens: Pair<String, String>? = null,
        extraJson: String = "",
    ): String {
        val rotated = rotatedTokens?.let { (access, refresh) ->
            ""","rotatedTokens":{"accessToken":"${esc(access)}","refreshToken":"${esc(refresh)}"}"""
        } ?: ""
        val extra = if (extraJson.isEmpty()) "" else ",$extraJson"
        return """{"itemId":"i1","speakerId":"s1","state":"${esc(state)}","positionSeconds":$positionSeconds,""" +
            """"durationSeconds":$durationSeconds,"updatedAt":"2026-07-05T12:00:00Z"$rotated$extra}"""
    }

    /** An `AuthTokens` response body, as returned by login and refresh. */
    fun authTokensJson(
        accessToken: String = "a1",
        refreshToken: String = "r1",
        username: String = "alex",
    ): String =
        """{"accessToken":"${esc(accessToken)}","refreshToken":"${esc(refreshToken)}",""" +
            """"user":{"id":"7","username":"${esc(username)}"}}"""

    /** A single `Speaker` wire object. */
    fun speakerJson(
        id: String = "s1",
        name: String = "Living Room",
        isGroup: Boolean = false,
        members: List<String> = emptyList(),
    ): String {
        val m = members.joinToString(",") { "\"${esc(it)}\"" }
        return """{"id":"${esc(id)}","name":"${esc(name)}","isGroup":$isGroup,"members":[$m]}"""
    }

    /** A `GET /v1/speakers` response body (a JSON array of speakers). */
    fun speakerListJson(vararg speakers: String = arrayOf(speakerJson())): String =
        "[${speakers.joinToString(",")}]"

    /**
     * A single `LibraryItemSummary` wire object (cover/progress omitted by default - both
     * optional). [coverUrl] is origin-relative on the wire since contract 1.3.x
     * (e.g. `/v1/library/items/i1/cover`); the client resolves it against its base URL.
     */
    fun libraryItemSummaryJson(
        id: String = "i1",
        title: String = "The Hobbit",
        author: String? = "J. R. R. Tolkien",
        durationSeconds: Double = 39_600.0,
        coverUrl: String? = null,
    ): String {
        val a = author?.let { ""","author":"${esc(it)}"""" } ?: ""
        val c = coverUrl?.let { ""","coverUrl":"${esc(it)}"""" } ?: ""
        return """{"id":"${esc(id)}","title":"${esc(title)}"$a$c,"durationSeconds":$durationSeconds}"""
    }

    /** A `GET /v1/library/items` response body (a `LibraryItemPage`). */
    fun libraryPageJson(
        items: List<String> = listOf(libraryItemSummaryJson()),
        nextCursor: String? = null,
    ): String {
        val c = nextCursor?.let { ""","nextCursor":"${esc(it)}"""" } ?: ""
        return """{"items":[${items.joinToString(",")}]$c}"""
    }

    /** A `GET /v1/library/in-progress` response body (a `LibraryItemList` - no cursor). */
    fun inProgressShelfJson(
        items: List<String> = listOf(libraryItemSummaryJson()),
    ): String = """{"items":[${items.joinToString(",")}]}"""

    /** Minimal JSON string escaping for interpolated values: backslash and double-quote. */
    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
