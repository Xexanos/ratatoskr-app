/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import io.github.xexanos.ratatoskr.network.domain.AuthSession

/**
 * What the auth plumbing needs from token storage. [TokenStore] is the Keystore-backed
 * implementation; tests use in-memory fakes (SPEC section 9).
 */
interface TokenAccess {
    suspend fun authSession(): AuthSession?
    suspend fun save(session: AuthSession)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun refreshToken(): String?
    suspend fun clear()

    /**
     * Blocking read of the current access token, for OkHttp interceptors which run on a
     * background thread and cannot suspend.
     */
    fun currentAccessTokenBlocking(): String?
}
