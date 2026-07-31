/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.Credential

/**
 * What the auth plumbing needs from token storage: the full /v1 token *pair* plus the bearer
 * read the interceptors use. [TokenStore] is the Keystore-backed implementation; tests use
 * in-memory fakes (SPEC section 9).
 *
 * This is the pair-shaped side of storage - the network module's concern. The app module talks
 * only to the narrower [CredentialStore] this extends; the /v2 cutover collapses the pair here
 * into one Ratatoskr token, leaving [CredentialStore]'s callers untouched (SPEC section 5).
 */
interface TokenAccess : CredentialStore {
    suspend fun authSession(): AuthSession?
    suspend fun save(session: AuthSession)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun refreshToken(): String?
    // clear() is inherited from CredentialStore.

    /**
     * Blocking read of the current access token, for OkHttp interceptors which run on a
     * background thread and cannot suspend.
     */
    fun currentAccessTokenBlocking(): String?

    /**
     * The credential-shaped projection of the /v1 pair (SPEC section 5): present exactly when a
     * full session is stored, its value the bearer the client sends. Implementations that store
     * the pair get this for free; the /v2 cutover replaces the projection with a direct read of
     * the single Ratatoskr token.
     */
    override suspend fun credential(): Credential? = authSession()?.let { Credential(it.accessToken) }
}
