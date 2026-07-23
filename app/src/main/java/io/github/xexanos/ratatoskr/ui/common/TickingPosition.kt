/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A source of the current time, injected as a parameter (not a global) so
 * [tickingPositionSeconds] is deterministic under test - the one new seam the ticking
 * derivation needed (decision record, issue #79/#101).
 */
fun interface TickClock {
    fun nowMillis(): Long
}

object SystemTickClock : TickClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * The displayed position for a ticking player: the session's last polled position plus elapsed
 * wall-clock time since [receivedAtMillis] (the moment this reading was observed), clamped to
 * the session's duration - only while playing. Frozen at the polled value for every other state
 * (paused, buffering, ...) so the display never counts time that isn't being played.
 * Presentation-only: the underlying [Session] stays pure server truth. Shared by the mini
 * player and the Now-playing slider so both surfaces tell the same story.
 */
fun tickingPositionSeconds(session: Session, receivedAtMillis: Long, clock: TickClock = SystemTickClock): Double {
    if (session.state != PlaybackState.PLAYING) return session.positionSeconds
    // Clock skew (or a receipt time from the future) must never run time backwards.
    val elapsedSeconds = (clock.nowMillis() - receivedAtMillis).coerceAtLeast(0L) / 1000.0
    return (session.positionSeconds + elapsedSeconds).coerceIn(0.0, session.durationSeconds.coerceAtLeast(0.0))
}

/**
 * Remembers [session]'s ticking position, re-anchoring the receipt time whenever a new [session]
 * arrives (every poll re-anchors, per [tickingPositionSeconds]) and advancing once per second
 * while playing so the display creeps forward instead of jumping in poll steps.
 */
@Composable
fun rememberTickingPositionSeconds(session: Session, clock: TickClock = SystemTickClock): Double {
    val receivedAtMillis = remember(session) { clock.nowMillis() }
    var tick by remember(session) { mutableIntStateOf(0) }
    LaunchedEffect(session) {
        if (session.state == PlaybackState.PLAYING) {
            while (isActive) {
                delay(1_000)
                tick++
            }
        }
    }
    return remember(receivedAtMillis, tick) { tickingPositionSeconds(session, receivedAtMillis, clock) }
}

/** "H:MM:SS" once past the hour, else "M:SS" - shared by the mini player and Now Playing. */
fun formatPlaybackTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
