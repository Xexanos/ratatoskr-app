/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network

import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import java.util.concurrent.atomic.AtomicReference

/** In-memory [TokenAccess] for JVM unit tests (SPEC section 9: platform pieces faked). */
class FakeTokenAccess(
    accessToken: String? = null,
    refreshToken: String? = null,
) : TokenAccess {

    private data class Tokens(val access: String?, val refresh: String?)

    private val tokens = AtomicReference(Tokens(accessToken, refreshToken))
    var savedSession: AuthSession? = null
        private set

    override suspend fun authSession(): AuthSession? = savedSession

    override suspend fun save(session: AuthSession) {
        savedSession = session
        tokens.set(Tokens(session.accessToken, session.refreshToken))
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        tokens.set(Tokens(accessToken, refreshToken))
    }

    override suspend fun refreshToken(): String? = tokens.get().refresh

    override suspend fun clear() {
        tokens.set(Tokens(null, null))
        savedSession = null
    }

    override fun currentAccessTokenBlocking(): String? = tokens.get().access
}
