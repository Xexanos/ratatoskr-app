/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 5_000L

// One definition of "active" for every consumer (CONTEXT.md "Active session"): gates both the
// client's own token-refresh suppression and the mini player's existence (decision record,
// issue #79/#101 #4). A session in a stopped/finished tail is already not active.
private val ACTIVE_STATES = setOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING)

data class SessionSnapshot(
    val loading: Boolean = true,
    val session: Session? = null,
    // The last poll's error, if any - kept separate from a lost [session] (stale beats error).
    // Consumers decide whether to surface it: Now Playing shows a banner, the mini player
    // ignores it entirely (no error state - decision record #8).
    val error: RatatoskrError? = null,
) {
    val active: Boolean get() = session?.state in ACTIVE_STATES
}

/**
 * The single, process-wide owner of playback session truth (decision record, issue #79/#101):
 * the poll loop, the transport commands (pause/resume/seek/stop), the command-epoch guard
 * against poll/command races, `NoActiveSession` handling, and the `sessionActive`
 * token-rotation flag - the only writer of that flag (SPEC section 5). ViewModels are thin
 * adapters over [state]; they never poll or hold this logic themselves.
 *
 * Bound to [androidx.lifecycle.ProcessLifecycleOwner] by the app-level wiring so polling
 * follows app foreground/background, not any one screen. [poll] itself is a plain suspend
 * function so tests can drive single ticks deterministically instead of racing a live timer.
 */
class SessionManager(private val connectionManager: ConnectionManager) : DefaultLifecycleObserver {

    private val _state = MutableStateFlow(SessionSnapshot())
    val state: StateFlow<SessionSnapshot> = _state.asStateFlow()

    // Monotonic counter of control actions (pause/resume/seek/stop). Each bumps it; a poll (or
    // an overtaken control) snapshots it at the start and drops its own result if a newer
    // control was issued meanwhile - so a poll or a superseded command already in flight when
    // the user acts again can't flip state back or surface a stale failure.
    private var commandEpoch = 0

    private var pollScope: CoroutineScope? = null
    private var pollJob: Job? = null

    /**
     * One poll tick: fetches the current session and applies it, or - on `NoActiveSession` -
     * clears it and releases the token-rotation flag (the session ended: the server
     * relinquished it, or it was stopped elsewhere). Any other failure keeps the last known
     * session and only records the error for consumers that want to show it - a transient poll
     * blip must never blank a live session.
     */
    suspend fun poll() {
        val epoch = commandEpoch
        val client = connectionManager.client() ?: return
        when (val result = client.currentSession()) {
            is ApiResult.Success -> applySession(result.data, epoch)
            is ApiResult.Failure -> {
                // A control action issued while this poll was in flight takes precedence.
                if (epoch != commandEpoch) return
                when (result.error) {
                    is RatatoskrError.NoActiveSession -> {
                        connectionManager.setSessionActive(false)
                        _state.value = _state.value.copy(loading = false, session = null, error = null)
                    }
                    else -> {
                        result.error.maybeRequireReauth()
                        _state.value = _state.value.copy(loading = false, error = result.error)
                    }
                }
            }
        }
    }

    /** Starts the 5s poll loop; cancels and replaces any loop already running. */
    override fun onStart(owner: LifecycleOwner) {
        stopPollingInternal()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        pollScope = scope
        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the poll loop - the app is backgrounded, so it neither hits the server nor drains the battery. */
    override fun onStop(owner: LifecycleOwner) = stopPollingInternal()

    private fun stopPollingInternal() {
        pollJob?.cancel()
        pollJob = null
        pollScope?.cancel()
        pollScope = null
    }

    /** Drops back to the initial snapshot - a stale session from a previous server must not
     * leak into the next one (used by the instrumented tests to start each from a clean
     * install; mirrors [SpeakerManager.reset]). */
    fun reset() {
        _state.value = SessionSnapshot()
    }

    suspend fun pause(): ApiResult<Session>? = control { it.pause() }
    suspend fun resume(): ApiResult<Session>? = control { it.resume() }
    suspend fun seek(positionSeconds: Double): ApiResult<Session>? = control { it.seek(positionSeconds) }

    /**
     * Stops playback. Epoch-guarded like every other control action: a stale in-flight poll
     * that resolves after this lands must not revive the session this just ended.
     */
    suspend fun stop(): ApiResult<Unit>? {
        val epoch = ++commandEpoch
        val client = connectionManager.client() ?: return null
        return when (val result = client.stopSession()) {
            is ApiResult.Success -> {
                if (epoch == commandEpoch) {
                    connectionManager.setSessionActive(false)
                    _state.value = _state.value.copy(loading = false, session = null, error = null)
                }
                ApiResult.Success(Unit)
            }
            // A superseded stop's failure is suppressed (null), same rule as every other control.
            is ApiResult.Failure ->
                if (epoch == commandEpoch) {
                    result.error.maybeRequireReauth()
                    result
                } else {
                    null
                }
        }
    }

    /**
     * Runs [action], bumping [commandEpoch] first so a poll (or an older control) already in
     * flight can't overwrite this result. Returns null when a newer action has superseded this
     * one by the time it completes - the caller does nothing rather than show a stale failure
     * or apply a stale success.
     */
    private suspend fun control(action: suspend (RatatoskrClient) -> ApiResult<Session>): ApiResult<Session>? {
        val epoch = ++commandEpoch
        val client = connectionManager.client() ?: return null
        return when (val result = action(client)) {
            is ApiResult.Success -> {
                applySession(result.data, epoch)
                result
            }
            is ApiResult.Failure ->
                if (epoch == commandEpoch) {
                    result.error.maybeRequireReauth()
                    result
                } else {
                    null
                }
        }
    }

    /**
     * A terminal [RatatoskrError.Unauthorized] is a token lapse the app cannot silently recover
     * from during an active session (SPEC section 5): the access token expired while polling
     * suppressed the client's own refresh, and by the time the failure surfaces here the refresh
     * token has typically rotated away too. Hand it to the connection manager, which discards
     * the stranded tokens and signals the nav host to route back to sign-in. Any other error is
     * left to the caller (poll()/stop()/control()'s caller) to show in its own card/banner.
     */
    private suspend fun RatatoskrError.maybeRequireReauth() {
        if (this is RatatoskrError.Unauthorized) connectionManager.requireReauth()
    }

    private fun applySession(session: Session, epoch: Int) {
        // Drop a result an intervening control action has already superseded (e.g. a poll, or
        // an older command, that was in flight when a newer one landed).
        if (epoch != commandEpoch) return
        // The server owns token rotation while a session is active (SPEC section 5).
        connectionManager.setSessionActive(session.state in ACTIVE_STATES)
        _state.value = _state.value.copy(loading = false, session = session, error = null)
    }
}
