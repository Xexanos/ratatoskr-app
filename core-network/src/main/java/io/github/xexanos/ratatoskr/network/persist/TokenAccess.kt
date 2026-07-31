/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.Credential

/**
 * What the auth plumbing needs from token storage: the stored Ratatoskr token plus the bearer
 * read the interceptors use. [TokenStore] is the Keystore-backed implementation; tests use
 * in-memory fakes (SPEC section 9).
 *
 * This is the token-shaped side of storage - the network module's concern. The app module talks
 * only to the narrower [CredentialStore] this extends (SPEC section 5).
 */
interface TokenAccess : CredentialStore {
    suspend fun authSession(): AuthSession?
    suspend fun save(session: AuthSession)
    // clear() is inherited from CredentialStore.

    /**
     * Blocking read of the current Ratatoskr token, for OkHttp interceptors which run on a
     * background thread and cannot suspend.
     */
    fun currentTokenBlocking(): String?

    /**
     * The credential-shaped projection of the stored session (SPEC section 5): present exactly
     * when a full session is stored, its value the bearer the client sends.
     */
    override suspend fun credential(): Credential? = authSession()?.let { Credential(it.token) }
}
