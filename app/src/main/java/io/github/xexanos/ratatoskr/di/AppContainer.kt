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
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.xexanos.ratatoskr.covers.CoverImages
import io.github.xexanos.ratatoskr.data.ConnectionManager
import io.github.xexanos.ratatoskr.data.SessionManager
import io.github.xexanos.ratatoskr.data.SpeakerManager
import io.github.xexanos.ratatoskr.network.persist.DataStoreConnectionStore
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

    val connectionStore = DataStoreConnectionStore(connectionDataStore)
    val tokenStore = TokenStore(tokenDataStore, KeystoreCrypto())
    val certificateInspector = CertificateInspector()
    val connectionManager = ConnectionManager(connectionStore, tokenStore)
    val coverImages = CoverImages(appContext) { connectionManager.peekClient()?.coversCallFactory }
    val speakerManager = SpeakerManager(connectionManager)

    // Process-wide session polling (decision record, issue #79/#101): bound to app
    // foreground/background here rather than any one screen, so the poll loop outlives
    // screen navigation and stops only when the whole app is backgrounded.
    val sessionManager = SessionManager(connectionManager).also {
        ProcessLifecycleOwner.get().lifecycle.addObserver(it)
    }

    /**
     * Clears all locally persisted state (trusted server + certificate, auth tokens, cached
     * cover images) and drops the cached client. Lives next to the store declarations so a
     * newly added store is covered here by construction; used by the instrumented tests to
     * start each from a clean install.
     */
    suspend fun reset() {
        connectionStore.clear()
        tokenStore.clear()
        connectionManager.invalidate()
        coverImages.clear()
        sessionManager.reset()
        speakerManager.reset()
    }
}
