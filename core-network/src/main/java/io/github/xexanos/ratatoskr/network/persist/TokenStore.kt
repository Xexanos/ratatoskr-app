/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Persists the auth tokens encrypted at rest (SPEC section 5). Token values are encrypted
 * with [KeystoreCrypto] before being written to DataStore; the username/id are not secret
 * and are stored as-is.
 *
 * Sign-out ([clear]) removes everything. Tokens, passwords and headers are never logged
 * (SPEC section 11).
 */
class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val crypto: KeystoreCrypto,
) {

    suspend fun authSession(): AuthSession? {
        val prefs = dataStore.data.first()
        val access = prefs[ACCESS_TOKEN]?.let(crypto::decrypt) ?: return null
        val refresh = prefs[REFRESH_TOKEN]?.let(crypto::decrypt) ?: return null
        val userId = prefs[USER_ID] ?: return null
        val username = prefs[USERNAME] ?: return null
        return AuthSession(access, refresh, AuthUser(userId, username))
    }

    suspend fun save(session: AuthSession) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = crypto.encrypt(session.accessToken)
            prefs[REFRESH_TOKEN] = crypto.encrypt(session.refreshToken)
            prefs[USER_ID] = session.user.id
            prefs[USERNAME] = session.user.username
        }
    }

    /** Replace just the token pair after a refresh or a server-side rotation (SPEC section 5). */
    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = crypto.encrypt(accessToken)
            prefs[REFRESH_TOKEN] = crypto.encrypt(refreshToken)
        }
    }

    suspend fun refreshToken(): String? =
        dataStore.data.first()[REFRESH_TOKEN]?.let(crypto::decrypt)

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * Blocking read of the current access token, for the OkHttp auth interceptor which runs
     * on a background thread and cannot suspend. DataStore keeps values in memory after the
     * first read, so this is cheap.
     */
    fun currentAccessTokenBlocking(): String? = runBlocking {
        dataStore.data.first()[ACCESS_TOKEN]?.let(crypto::decrypt)
    }

    private companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
    }
}
