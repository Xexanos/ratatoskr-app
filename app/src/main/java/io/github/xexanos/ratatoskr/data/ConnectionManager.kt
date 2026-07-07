/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.TokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the current [RatatoskrClient], rebuilding it whenever the trusted server or its
 * fingerprint changes. Returns null until the user has confirmed a server certificate
 * (SPEC section 6), which is how the UI knows to route to the connect screen.
 */
class ConnectionManager(
    val connectionStore: ConnectionStore,
    val tokenStore: TokenStore,
) {
    // While a playback session is active the server owns refresh-token rotation, so the
    // client must not refresh independently (SPEC section 5).
    private val sessionActive = AtomicBoolean(false)

    // Guards client construction so concurrent callers (e.g. the poll loop and a screen right
    // after sign-in) cannot each build a client. Two OkHttp stacks would each hold their own
    // single-flight refresh lock and could refresh the same rotating token twice, invalidating
    // the pair and signing the user out (SPEC section 5). @Volatile makes the fast-path read
    // see the winner's write.
    private val buildMutex = Mutex()

    // The client and the key it was built for, held as one object so the lock-free fast path
    // reads them in a single volatile load and can never observe a torn (client, key) pair.
    private data class Cached(val key: String, val client: RatatoskrClient)

    @Volatile private var cached: Cached? = null

    fun setSessionActive(active: Boolean) = sessionActive.set(active)

    /** The client for the currently trusted server, or null if none is configured yet. */
    suspend fun client(): RatatoskrClient? {
        val config = connectionStore.currentServerConfig() ?: return null
        val fingerprint = connectionStore.fingerprint() ?: return null
        val key = "${config.baseUrl}|$fingerprint"
        cached?.let { if (it.key == key) return it.client }
        return buildMutex.withLock {
            // Re-check inside the lock: another caller may have built it while we waited.
            cached?.let { if (it.key == key) return@withLock it.client }
            // A cached client for a different key is being replaced - release its HTTP stack.
            cached?.client?.close()
            RatatoskrClientFactory.create(
                baseUrl = config.baseUrl,
                fingerprint = fingerprint,
                tokenStore = tokenStore,
                sessionActive = { sessionActive.get() },
            ).also {
                cached = Cached(key, it)
            }
        }
    }

    /** Drop the cached client after the server or certificate changed, releasing its HTTP stack. */
    fun invalidate() {
        cached?.client?.close()
        cached = null
    }
}
