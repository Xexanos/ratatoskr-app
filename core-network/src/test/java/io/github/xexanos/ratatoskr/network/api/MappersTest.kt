/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.generated.infrastructure.Serializer
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItem as GenLibraryItem
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItemSummary as GenLibraryItemSummary
import io.github.xexanos.ratatoskr.network.generated.model.PlaybackState as GenPlaybackState
import io.github.xexanos.ratatoskr.network.generated.model.Progress as GenProgress
import io.github.xexanos.ratatoskr.network.generated.model.Session as GenSession
import io.github.xexanos.ratatoskr.network.generated.model.Speaker as GenSpeaker
import com.squareup.moshi.JsonDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.OffsetDateTime

class MappersTest {

    @Test
    fun `flattened generated LibraryItem maps into summary plus detail`() {
        val gen = GenLibraryItem(
            id = "42",
            title = "The Hobbit",
            durationSeconds = 39600.0,
            author = "J. R. R. Tolkien",
            coverUrl = "https://example/cover",
            progress = GenProgress(positionSeconds = 120.5, isFinished = false),
            description = "There and back again",
            narrator = "Andy Serkis",
        )

        val domain = gen.toDomain(baseUrl = "https://ratatoskr.home:8080")

        assertEquals("42", domain.summary.id)
        assertEquals("The Hobbit", domain.summary.title)
        assertEquals("J. R. R. Tolkien", domain.summary.author)
        assertEquals(120.5, domain.summary.progress!!.positionSeconds, 0.0)
        assertEquals("There and back again", domain.description)
        assertEquals("Andy Serkis", domain.narrator)
    }

    // --- coverUrl resolution (contract 1.3.x sends a path relative to the server origin;
    // --- the wrapper absorbs that so the domain model always carries a loadable URL) --------

    @Test
    fun `relative coverUrl resolves against the base url`() {
        assertEquals(
            "https://ratatoskr.home:8080/v1/library/items/42/cover",
            resolveCoverUrl("/v1/library/items/42/cover", "https://ratatoskr.home:8080"),
        )
    }

    @Test
    fun `trailing slash on the base url does not double the separator`() {
        assertEquals(
            "https://ratatoskr.home:8080/v1/library/items/42/cover",
            resolveCoverUrl("/v1/library/items/42/cover", "https://ratatoskr.home:8080/"),
        )
    }

    @Test
    fun `absolute coverUrl passes through untouched`() {
        // Tolerant-reader guard: contract 1.1.0 described the field as an absolute URL, and a
        // future server could return to that. Never re-prefix an already-absolute value.
        assertEquals(
            "https://elsewhere.example/cover.jpg",
            resolveCoverUrl("https://elsewhere.example/cover.jpg", "https://ratatoskr.home:8080"),
        )
    }

    @Test
    fun `null coverUrl stays null`() {
        assertEquals(null, resolveCoverUrl(null, "https://ratatoskr.home:8080"))
    }

    @Test
    fun `summary mapping resolves the cover url`() {
        val gen = GenLibraryItemSummary(
            id = "42",
            title = "The Hobbit",
            durationSeconds = 39600.0,
            coverUrl = "/v1/library/items/42/cover",
        )

        val domain = gen.toDomain(baseUrl = "https://ratatoskr.home:8080")

        assertEquals("https://ratatoskr.home:8080/v1/library/items/42/cover", domain.coverUrl)
    }

    @Test
    fun `speaker members default to an empty list`() {
        val domain = GenSpeaker(id = "s", name = "Kitchen", isGroup = false, members = null).toDomain()
        assertEquals(emptyList<String>(), domain.members)
    }

    @Test
    fun `every playback state maps`() {
        assertEquals(PlaybackState.PLAYING, GenPlaybackState.playing.toDomain())
        assertEquals(PlaybackState.PAUSED, GenPlaybackState.paused.toDomain())
        assertEquals(PlaybackState.BUFFERING, GenPlaybackState.buffering.toDomain())
        assertEquals(PlaybackState.STOPPED, GenPlaybackState.stopped.toDomain())
        assertEquals(PlaybackState.FINISHED, GenPlaybackState.finished.toDomain())
    }

    @Test
    fun `session json with unknown fields still deserializes`() {
        // An older app must keep working against a newer server (SPEC section 4):
        // unknown fields anywhere in the payload must be ignored.
        val json = """
            {
              "itemId": "i1",
              "speakerId": "s1",
              "state": "playing",
              "positionSeconds": 12.5,
              "durationSeconds": 100.0,
              "updatedAt": "2026-07-05T12:00:00Z",
              "accessToken": "future-rotation-field",
              "someFutureField": {"nested": [1, 2, 3]},
              "item": {
                "id": "i1",
                "title": "T",
                "durationSeconds": 100.0,
                "anotherNewField": true
              }
            }
        """.trimIndent()

        val session = Serializer.moshi.adapter(GenSession::class.java).fromJson(json)

        assertNotNull(session)
        assertEquals("i1", session!!.itemId)
        assertEquals(PlaybackState.PLAYING, session.state.toDomain())
        assertEquals(OffsetDateTime.parse("2026-07-05T12:00:00Z"), session.updatedAt)
        assertEquals("T", session.item!!.title)
    }

    @Test
    fun `session json missing a required field is rejected`() {
        // The strict counterpart to unknown-field tolerance: a payload that lacks a required
        // field must fail deserialization, not produce a half-initialized object. Pins the
        // behaviour across Moshi adapter strategies (reflective vs codegen).
        val json = """
            {
              "itemId": "i1",
              "speakerId": "s1",
              "positionSeconds": 12.5,
              "durationSeconds": 100.0,
              "updatedAt": "2026-07-05T12:00:00Z"
            }
        """.trimIndent()

        assertThrows(JsonDataException::class.java) {
            Serializer.moshi.adapter(GenSession::class.java).fromJson(json)
        }
    }
}
