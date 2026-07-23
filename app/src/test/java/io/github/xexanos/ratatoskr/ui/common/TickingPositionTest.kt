/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.common

import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class TickingPositionTest {

    private fun session(state: PlaybackState, positionSeconds: Double = 10.0, durationSeconds: Double = 100.0) =
        Session(
            itemId = "i1",
            item = null,
            speakerId = "s1",
            state = state,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
            updatedAt = OffsetDateTime.parse("2026-07-05T12:00:00Z"),
        )

    @Test
    fun `playing advances by the elapsed time since it was received`() {
        val position = tickingPositionSeconds(
            session = session(PlaybackState.PLAYING, positionSeconds = 10.0),
            receivedAtMillis = 1_000L,
            clock = { 3_500L }, // 2.5s elapsed
        )

        assertEquals(12.5, position, 0.001)
    }

    @Test
    fun `paused stays frozen at the polled value regardless of elapsed time`() {
        val position = tickingPositionSeconds(
            session = session(PlaybackState.PAUSED, positionSeconds = 10.0),
            receivedAtMillis = 1_000L,
            clock = { 60_000L },
        )

        assertEquals(10.0, position, 0.001)
    }

    @Test
    fun `buffering stays frozen at the polled value`() {
        val position = tickingPositionSeconds(
            session = session(PlaybackState.BUFFERING, positionSeconds = 10.0),
            receivedAtMillis = 1_000L,
            clock = { 60_000L },
        )

        assertEquals(10.0, position, 0.001)
    }

    @Test
    fun `position clamps at the session duration`() {
        val position = tickingPositionSeconds(
            session = session(PlaybackState.PLAYING, positionSeconds = 95.0, durationSeconds = 100.0),
            receivedAtMillis = 1_000L,
            clock = { 60_000L }, // 59s elapsed - would overshoot 100 without clamping
        )

        assertEquals(100.0, position, 0.001)
    }

    @Test
    fun `clock skew before the receipt time never subtracts elapsed time`() {
        val position = tickingPositionSeconds(
            session = session(PlaybackState.PLAYING, positionSeconds = 10.0),
            receivedAtMillis = 5_000L,
            clock = { 1_000L }, // clock reads earlier than the receipt time
        )

        assertEquals(10.0, position, 0.001)
    }

    @Test
    fun `re-anchoring on a fresh receipt time uses only the elapsed time since then`() {
        val polled = session(PlaybackState.PLAYING, positionSeconds = 50.0)

        val position = tickingPositionSeconds(polled, receivedAtMillis = 10_000L, clock = { 11_000L })

        assertEquals(51.0, position, 0.001)
    }
}
