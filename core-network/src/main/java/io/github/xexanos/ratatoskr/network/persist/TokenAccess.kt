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
     * The display username of the last signed-in user, if one is remembered - independent of the
     * token, so it survives [clearToken]. Backs the sign-in screen's pre-fill on the 401
     * re-authentication path (SPEC section 5): only the password is blank.
     */
    suspend fun username(): String?

    /**
     * Discards the token but keeps the remembered username - the 401 re-authentication path
     * (SPEC section 5), where server URL, certificate trust, and username all survive and only
     * the password is re-entered. After this, [authSession]/[credential] read null (signed out),
     * but [username] still pre-fills the sign-in screen. Full sign-out uses [clear] instead,
     * which forgets the username too.
     */
    suspend fun clearToken()

    /**
     * The one-time /v1 -> /v2 migration (SPEC section 5): discards the Audiobookshelf token
     * pair a pre-update install left behind, keeping the remembered username like [clearToken]
     * does. Returns whether there was a pair to discard - true exactly once, on the first
     * launch after the update; a no-op false on every other launch.
     */
    suspend fun discardLegacyTokens(): Boolean

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
