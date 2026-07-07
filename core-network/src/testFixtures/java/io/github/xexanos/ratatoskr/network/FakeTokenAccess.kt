/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network

import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory [TokenAccess] shared by the JVM unit tests and the instrumented integration
 * tests (SPEC section 9: platform pieces faked). Tests where persistence itself is the
 * point use the real Keystore-backed TokenStore instead.
 *
 * All views derive from ONE atomic state, mirroring the real store's semantics: after
 * [updateTokens], [authSession] reports the rotated pair rather than a stale snapshot,
 * and it returns null unless tokens AND a user are present (a token-only seed has no
 * signed-in user, exactly like the real store).
 */
class FakeTokenAccess(
    accessToken: String? = null,
    refreshToken: String? = null,
    user: AuthUser? = null,
) : TokenAccess {

    private data class State(val access: String?, val refresh: String?, val user: AuthUser?)

    private val state = AtomicReference(State(accessToken, refreshToken, user))

    /** The session as the current state reports it - null unless tokens and user are present. */
    val savedSession: AuthSession?
        get() = state.get().let { s ->
            if (s.access != null && s.refresh != null && s.user != null) {
                AuthSession(s.access, s.refresh, s.user)
            } else {
                null
            }
        }

    override suspend fun authSession(): AuthSession? = savedSession

    override suspend fun save(session: AuthSession) {
        state.set(State(session.accessToken, session.refreshToken, session.user))
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        state.updateAndGet { State(accessToken, refreshToken, it.user) }
    }

    override suspend fun refreshToken(): String? = state.get().refresh

    override suspend fun clear() {
        state.set(State(null, null, null))
    }

    override fun currentAccessTokenBlocking(): String? = state.get().access
}
