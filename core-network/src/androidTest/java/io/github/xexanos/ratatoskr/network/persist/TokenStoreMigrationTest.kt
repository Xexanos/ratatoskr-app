/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The /v1 -> /v2 migration seam of [TokenStore] (SPEC section 5): a pre-update install left an
 * Audiobookshelf token pair under keys the /v2 store no longer reads. Instrumented like
 * [KeystoreCryptoTest] because the store is exercised against a real preferences DataStore
 * file (the JVM's non-replacing File.renameTo on Windows breaks DataStore's atomic-rename
 * write); the crypto itself is never invoked - the migration removes ciphertext without
 * decrypting it.
 */
@RunWith(AndroidJUnit4::class)
class TokenStoreMigrationTest {

    // The keys exactly as the /v1 app wrote them - frozen history, not shared constants.
    private val legacyAccessToken = stringPreferencesKey("access_token")
    private val legacyRefreshToken = stringPreferencesKey("refresh_token")
    private val userId = stringPreferencesKey("user_id")
    private val username = stringPreferencesKey("username")
    private val ratatoskrToken = stringPreferencesKey("ratatoskr_token")

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // A fresh file per test: DataStore allows only one active instance per file, and the
    // instrumentation process is shared across tests and runs.
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        InstrumentationRegistry.getInstrumentation().targetContext
            .cacheDir.resolve("token_store_migration_${System.nanoTime()}.preferences_pb")
    }

    private val store = TokenStore(dataStore, KeystoreCrypto())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun discardsTheV1TokenPairAndItsUserIdKeepsTheUsername() = runBlocking {
        dataStore.edit {
            it[legacyAccessToken] = "ciphertext-access"
            it[legacyRefreshToken] = "ciphertext-refresh"
            it[userId] = "7"
            it[username] = "alex"
        }

        assertTrue(store.discardLegacyTokens())

        val after = dataStore.data.first()
        assertNull(after[legacyAccessToken])
        assertNull(after[legacyRefreshToken])
        assertNull(after[userId])
        // The username survives for the sign-in pre-fill (SPEC section 5 migration).
        assertEquals("alex", after[username])
    }

    @Test
    fun runsOnceTheSecondCallFindsNothingToDiscard() = runBlocking {
        dataStore.edit { it[legacyAccessToken] = "ciphertext-access" }

        assertTrue(store.discardLegacyTokens())
        assertFalse(store.discardLegacyTokens())
    }

    @Test
    fun aCleanInstallHasNothingToDiscard() = runBlocking {
        assertFalse(store.discardLegacyTokens())
    }

    @Test
    fun aSignedInV2StoreIsLeftUntouched() = runBlocking {
        dataStore.edit {
            it[ratatoskrToken] = "ciphertext-token"
            it[userId] = "7"
            it[username] = "alex"
        }

        assertFalse(store.discardLegacyTokens())

        val after = dataStore.data.first()
        assertEquals("ciphertext-token", after[ratatoskrToken])
        assertEquals("7", after[userId])
        assertEquals("alex", after[username])
    }
}
