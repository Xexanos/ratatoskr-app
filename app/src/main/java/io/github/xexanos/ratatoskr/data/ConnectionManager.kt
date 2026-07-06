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

    @Volatile private var cached: RatatoskrClient? = null
    @Volatile private var cachedKey: String? = null

    fun setSessionActive(active: Boolean) = sessionActive.set(active)

    /** The client for the currently trusted server, or null if none is configured yet. */
    suspend fun client(): RatatoskrClient? {
        val config = connectionStore.currentServerConfig() ?: return null
        val fingerprint = connectionStore.fingerprint() ?: return null
        val key = "${config.baseUrl}|$fingerprint"
        cached?.let { if (cachedKey == key) return it }
        return buildMutex.withLock {
            // Re-check inside the lock: another caller may have built it while we waited.
            cached?.let { if (cachedKey == key) return@withLock it }
            RatatoskrClientFactory.create(
                baseUrl = config.baseUrl,
                fingerprint = fingerprint,
                tokenStore = tokenStore,
                sessionActive = { sessionActive.get() },
            ).also {
                cached = it
                cachedKey = key
            }
        }
    }

    /** Drop the cached client after the server or certificate changed. */
    fun invalidate() {
        cached = null
        cachedKey = null
    }
}
