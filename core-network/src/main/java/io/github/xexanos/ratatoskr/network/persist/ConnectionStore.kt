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
 * URL and the confirmed server-certificate fingerprint. Not encrypted - a certificate hash
 * and a URL are public. The auth tokens live in [TokenAccess] instead.
 *
 * [DataStoreConnectionStore] is the on-disk implementation; tests use in-memory fakes
 * (SPEC section 9), mirroring [TokenAccess]/[TokenStore].
 */
interface ConnectionStore {

    val serverConfig: Flow<ServerConfig?>

    suspend fun currentServerConfig(): ServerConfig?

    suspend fun fingerprint(): String?

    /** Persist a confirmed server + its trusted fingerprint (trust-on-first-use, SPEC section 6). */
    suspend fun saveTrustedServer(baseUrl: String, fingerprint: String)

    /** Forget the trusted certificate (re-trust flow), keeping the URL for convenience. */
    suspend fun forgetFingerprint()

    suspend fun clear()
}

/** DataStore-Preferences backed [ConnectionStore] - the store used in the running app. */
class DataStoreConnectionStore(
    private val dataStore: DataStore<Preferences>,
) : ConnectionStore {

    override val serverConfig: Flow<ServerConfig?> = dataStore.data.map { prefs ->
        prefs[BASE_URL]?.let { ServerConfig(it) }
    }

    override suspend fun currentServerConfig(): ServerConfig? = serverConfig.first()

    override suspend fun fingerprint(): String? = dataStore.data.first()[FINGERPRINT]

    override suspend fun saveTrustedServer(baseUrl: String, fingerprint: String) {
        dataStore.edit { prefs ->
            prefs[BASE_URL] = baseUrl
            prefs[FINGERPRINT] = fingerprint
        }
    }

    override suspend fun forgetFingerprint() {
        dataStore.edit { it.remove(FINGERPRINT) }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("server_base_url")
        val FINGERPRINT = stringPreferencesKey("server_cert_fingerprint")
    }
}
