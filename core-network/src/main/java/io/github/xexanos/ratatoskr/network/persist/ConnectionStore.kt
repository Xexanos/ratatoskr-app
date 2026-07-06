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
import io.github.xexanos.ratatoskr.network.domain.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The non-secret connection settings the app keeps per install (SPEC section 7): the server
 * URL and the confirmed server-certificate fingerprint. Not encrypted -- a certificate hash
 * and a URL are public. The auth tokens live in [TokenStore] instead.
 */
class ConnectionStore(
    private val dataStore: DataStore<Preferences>,
) {

    val serverConfig: Flow<ServerConfig?> = dataStore.data.map { prefs ->
        prefs[BASE_URL]?.let { ServerConfig(it) }
    }

    suspend fun currentServerConfig(): ServerConfig? = serverConfig.first()

    suspend fun fingerprint(): String? = dataStore.data.first()[FINGERPRINT]

    /** Persist a confirmed server + its trusted fingerprint (trust-on-first-use, SPEC section 6). */
    suspend fun saveTrustedServer(baseUrl: String, fingerprint: String) {
        dataStore.edit { prefs ->
            prefs[BASE_URL] = baseUrl
            prefs[FINGERPRINT] = fingerprint
        }
    }

    /** Forget the trusted certificate (re-trust flow), keeping the URL for convenience. */
    suspend fun forgetFingerprint() {
        dataStore.edit { it.remove(FINGERPRINT) }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("server_base_url")
        val FINGERPRINT = stringPreferencesKey("server_cert_fingerprint")
    }
}
