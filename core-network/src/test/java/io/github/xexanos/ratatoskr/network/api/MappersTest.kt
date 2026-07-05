/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.generated.infrastructure.Serializer
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItem as GenLibraryItem
import io.github.xexanos.ratatoskr.network.generated.model.PlaybackState as GenPlaybackState
import io.github.xexanos.ratatoskr.network.generated.model.Progress as GenProgress
import io.github.xexanos.ratatoskr.network.generated.model.Session as GenSession
import io.github.xexanos.ratatoskr.network.generated.model.Speaker as GenSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        val domain = gen.toDomain()

        assertEquals("42", domain.summary.id)
        assertEquals("The Hobbit", domain.summary.title)
        assertEquals("J. R. R. Tolkien", domain.summary.author)
        assertEquals(120.5, domain.summary.progress!!.positionSeconds, 0.0)
        assertEquals("There and back again", domain.description)
        assertEquals("Andy Serkis", domain.narrator)
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
}
