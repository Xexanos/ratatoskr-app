/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import io.github.xexanos.ratatoskr.network.domain.ApiResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves a speaker id to its display name for surfaces that only carry the id (the mini
 * player's "speaker" line; [Session.speakerId][io.github.xexanos.ratatoskr.network.domain.Session]
 * has no embedded name on the wire). Caches the whole list after the first fetch and refetches
 * only on a cache miss - an id not yet seen, or one the server has since renamed/replaced -
 * rather than on every lookup, since the speaker list rarely changes.
 */
class SpeakerManager(private val connectionManager: ConnectionManager) {

    @Volatile private var cache: Map<String, String> = emptyMap()

    // Guards refetching so concurrent misses for the same id (or different ids arriving at
    // once) coalesce into a single listSpeakers() call instead of one per caller.
    private val refreshMutex = Mutex()

    /**
     * The speaker's display name, or null if it is still unknown after a refetch - an
     * unconfigured server, a failed fetch (kept silent: no error state, mirrors the mini
     * player's own no-error-state decision), or an id the server doesn't recognise.
     */
    suspend fun nameFor(speakerId: String): String? {
        cache[speakerId]?.let { return it }
        refetchIfStillMissing(speakerId)
        return cache[speakerId]
    }

    private suspend fun refetchIfStillMissing(speakerId: String) {
        refreshMutex.withLock {
            // Another caller may have refreshed while this one waited for the lock.
            if (cache.containsKey(speakerId)) return
            val client = connectionManager.client() ?: return
            when (val result = client.listSpeakers()) {
                is ApiResult.Success -> cache = result.data.associate { it.id to it.name }
                is ApiResult.Failure -> {}
            }
        }
    }

    /** Drops the cached mapping - a stale cache from a previous server must not leak into the
     * next one (mirrors [AppContainer][io.github.xexanos.ratatoskr.di.AppContainer]'s other
     * per-server state, used by the instrumented tests to start each from a clean install). */
    fun reset() {
        cache = emptyMap()
    }
}
