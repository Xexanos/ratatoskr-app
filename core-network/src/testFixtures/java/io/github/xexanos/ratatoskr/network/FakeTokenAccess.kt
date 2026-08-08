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
 * All views derive from ONE atomic state, mirroring the real store's semantics: [authSession]
 * returns null unless a token AND a user are present (a token-only seed has no signed-in user,
 * exactly like the real store).
 */
class FakeTokenAccess(
    token: String? = null,
    user: AuthUser? = null,
) : TokenAccess {

    // retainedUsername survives clearToken (the 401 pre-fill), so it is held apart from the
    // token/user pair rather than derived from it.
    private data class State(val token: String?, val user: AuthUser?, val retainedUsername: String?)

    private val state = AtomicReference(State(token, user, user?.username))

    /** The session as the current state reports it - null unless a token and user are present. */
    val savedSession: AuthSession?
        get() = state.get().let { s ->
            if (s.token != null && s.user != null) AuthSession(s.token, s.user) else null
        }

    override suspend fun authSession(): AuthSession? = savedSession

    override suspend fun username(): String? = state.get().retainedUsername

    override suspend fun save(session: AuthSession) {
        state.set(State(session.token, session.user, session.user.username))
    }

    override suspend fun clearToken() {
        // Keep the remembered username; drop the token and user (SPEC section 5), mirroring the
        // real store.
        state.updateAndGet { State(null, null, it.retainedUsername) }
    }

    override suspend fun clear() {
        state.set(State(null, null, null))
    }

    override fun currentTokenBlocking(): String? = state.get().token
}
