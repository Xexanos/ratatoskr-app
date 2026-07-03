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

    private var cached: RatatoskrClient? = null
    private var cachedKey: String? = null

    fun setSessionActive(active: Boolean) = sessionActive.set(active)

    /** The client for the currently trusted server, or null if none is configured yet. */
    suspend fun client(): RatatoskrClient? {
        val config = connectionStore.currentServerConfig() ?: return null
        val fingerprint = connectionStore.fingerprint() ?: return null
        val key = "${config.baseUrl}|$fingerprint"
        val current = cached
        if (current != null && cachedKey == key) return current
        return RatatoskrClientFactory.create(
            baseUrl = config.baseUrl,
            fingerprint = fingerprint,
            tokenStore = tokenStore,
            sessionActive = { sessionActive.get() },
        ).also {
            cached = it
            cachedKey = key
        }
    }

    /** Drop the cached client after the server or certificate changed. */
    fun invalidate() {
        cached = null
        cachedKey = null
    }
}
