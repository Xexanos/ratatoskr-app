/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.Speaker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The shared cache of the speaker list, so a speaker's name/membership is consistent wherever
 * it's shown - the mini player's "speaker" line
 * ([Session.speakerId][io.github.xexanos.ratatoskr.network.domain.Session] has no embedded name
 * on the wire) and the Speakers screen's own list.
 *
 * There is no periodic background refresh: the speaker list rarely changes, so [nameFor]
 * refetches only on a cache miss (an id not yet seen, or one the server has since
 * renamed/replaced), and the Speakers screen calls [refresh] to force a fresh fetch each time it
 * loads - the moment a stale name/membership would actually matter to the user picking a
 * speaker. That refresh also keeps the mini player's cache current as a side effect.
 */
class SpeakerManager(private val connectionManager: ConnectionManager) {

    @Volatile private var cache: Map<String, Speaker> = emptyMap()

    // Guards refetching so concurrent misses for the same id (or different ids arriving at
    // once), or a miss racing an explicit refresh(), coalesce into a single listSpeakers() call.
    private val refreshMutex = Mutex()

    /**
     * The speaker's display name, or null if it is still unknown after a refetch - an
     * unconfigured server, a failed fetch (kept silent: no error state, mirrors the mini
     * player's own no-error-state decision), or an id the server doesn't recognise.
     */
    suspend fun nameFor(speakerId: String): String? {
        cache[speakerId]?.let { return it.name }
        refreshMutex.withLock {
            // Another caller may have refreshed while this one waited for the lock.
            if (!cache.containsKey(speakerId)) fetchAndCache()
        }
        return cache[speakerId]?.name
    }

    /**
     * Forces a fresh fetch of the whole speaker list, bypassing the cache - for a caller that
     * wants up-to-date data now (the Speakers screen, on load) rather than whatever's cached.
     * Returns the fetch result directly so the caller can render its own error state; null means
     * no server is configured. A failure leaves the existing cache untouched (stale beats error,
     * the same rule the mini player and the continue-listening shelf follow).
     */
    suspend fun refresh(): ApiResult<List<Speaker>>? = refreshMutex.withLock { fetchAndCache() }

    private suspend fun fetchAndCache(): ApiResult<List<Speaker>>? {
        val client = connectionManager.client() ?: return null
        return when (val result = client.listSpeakers()) {
            is ApiResult.Success -> {
                cache = result.data.associateBy { it.id }
                result
            }
            is ApiResult.Failure -> result
        }
    }

    /** Drops the cached mapping - a stale cache from a previous server must not leak into the
     * next one (mirrors [AppContainer][io.github.xexanos.ratatoskr.di.AppContainer]'s other
     * per-server state, used by the instrumented tests to start each from a clean install). */
    fun reset() {
        cache = emptyMap()
    }
}
