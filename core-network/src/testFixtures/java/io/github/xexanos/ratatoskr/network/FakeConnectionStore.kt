/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network

import io.github.xexanos.ratatoskr.network.domain.ServerConfig
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ConnectionStore] shared by the JVM unit tests and the instrumented integration
 * tests (SPEC section 9: platform pieces faked), mirroring [FakeTokenAccess].
 *
 * Reads and writes complete in-place with no dispatcher hop, so a ViewModel action that
 * persists through this store settles synchronously - unlike the real DataStore, whose writes
 * land on an IO dispatcher (datastore 1.2+) and whose file rename is flaky on Windows. Tests
 * that need to prove the real DataStore round-trip use [DataStoreConnectionStore] directly.
 */
class FakeConnectionStore(
    baseUrl: String? = null,
    fingerprint: String? = null,
) : ConnectionStore {

    private data class State(val baseUrl: String?, val fingerprint: String?)

    private val state = MutableStateFlow(State(baseUrl, fingerprint))

    override val serverConfig: Flow<ServerConfig?> =
        state.asStateFlow().map { it.baseUrl?.let(::ServerConfig) }

    override suspend fun currentServerConfig(): ServerConfig? =
        state.value.baseUrl?.let(::ServerConfig)

    override suspend fun fingerprint(): String? = state.value.fingerprint

    override suspend fun saveTrustedServer(baseUrl: String, fingerprint: String) {
        state.value = State(baseUrl, fingerprint)
    }

    override suspend fun forgetFingerprint() {
        state.value = state.value.copy(fingerprint = null)
    }

    override suspend fun clear() {
        state.value = State(null, null)
    }
}
