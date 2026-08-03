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
 * Persists the Ratatoskr token encrypted at rest (SPEC section 5). The token is encrypted
 * with [KeystoreCrypto] before being written to DataStore; the username/id are not secret
 * and are stored as-is.
 *
 * Sign-out ([clear]) removes everything. Tokens, passwords and headers are never logged
 * (SPEC section 11). The token is stored under a /v2-specific key, so a pre-/v2 install's
 * Audiobookshelf token pair is never read as a Ratatoskr token: it is simply absent here,
 * which routes the upgraded user to a fresh sign-in (SPEC section 5 migration).
 */
class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val crypto: KeystoreCrypto,
) : TokenAccess {

    override suspend fun authSession(): AuthSession? {
        val prefs = dataStore.data.first()
        val token = prefs[TOKEN]?.let(crypto::decrypt) ?: return null
        val userId = prefs[USER_ID] ?: return null
        val username = prefs[USERNAME] ?: return null
        return AuthSession(token, AuthUser(userId, username))
    }

    override suspend fun username(): String? = dataStore.data.first()[USERNAME]

    override suspend fun save(session: AuthSession) {
        dataStore.edit { prefs ->
            prefs[TOKEN] = crypto.encrypt(session.token)
            prefs[USER_ID] = session.user.id
            prefs[USERNAME] = session.user.username
        }
    }

    override suspend fun clearToken() {
        // Keep USERNAME for the sign-in pre-fill; drop the token and user id (SPEC section 5).
        dataStore.edit { prefs ->
            prefs.remove(TOKEN)
            prefs.remove(USER_ID)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * Blocking read of the current Ratatoskr token, for the OkHttp auth interceptor which runs
     * on a background thread and cannot suspend. DataStore keeps values in memory after the
     * first read, so this is cheap.
     */
    override fun currentTokenBlocking(): String? = runBlocking {
        dataStore.data.first()[TOKEN]?.let(crypto::decrypt)
    }

    private companion object {
        val TOKEN = stringPreferencesKey("ratatoskr_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
    }
}
