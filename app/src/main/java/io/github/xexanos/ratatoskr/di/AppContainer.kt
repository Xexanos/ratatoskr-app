/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.KeystoreCrypto
import io.github.xexanos.ratatoskr.network.persist.TokenStore
import io.github.xexanos.ratatoskr.network.tls.CertificateInspector

/**
 * Manual dependency container (SPEC section 12): one instance per process, created in the
 * Application, holding the singletons the screens need. No DI framework - the wiring is
 * visible in one place.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val connectionDataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("connection") }

    private val tokenDataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { appContext.preferencesDataStoreFile("tokens") }

    val connectionStore = ConnectionStore(connectionDataStore)
    val tokenStore = TokenStore(tokenDataStore, KeystoreCrypto())
    val certificateInspector = CertificateInspector()
    val connectionManager = ConnectionManager(connectionStore, tokenStore)
}
