/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.component

import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import io.github.xexanos.ratatoskr.network.persist.KeystoreCrypto
import io.github.xexanos.ratatoskr.network.persist.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * SPEC section 9, bullet (d): server-rotated session tokens adopted end-to-end, and the
 * `stopSession` 200-with-body vs 204 paths (section 5). Runs against the REAL Keystore-backed
 * [TokenStore] (AES/GCM via the Android Keystore, DataStore at rest) - so this verifies the
 * adoption actually persists through encryption on the device, not just an in-memory fake.
 */
@RunWith(AndroidJUnit4::class)
class FactorySessionRotationComponentTest {

    @get:Rule val https = HttpsMockServer()
    @get:Rule val testName = TestName()

    private var storeScope: CoroutineScope? = null

    // DataStore keeps its file registered process-wide until its scope dies; without this a
    // second DataStore on the same file (rerun, name collision) throws IllegalStateException.
    @After fun releaseDataStore() {
        storeScope?.cancel()
    }

    /**
     * A real Keystore-backed store, isolated per test. The DataStore file and key alias are
     * prefixed with the CLASS name, not just the method name: all instrumentation tests share
     * one process, so a second class adopting this pattern with a same-named test method must
     * not collide on the file.
     */
    private fun seededRealStore(): TokenStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "${javaClass.simpleName}_${testName.methodName}"
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { storeScope = it }
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(name)
        }
        val store = TokenStore(dataStore, KeystoreCrypto(keyAlias = "ratatoskr.test.$name"))
        runBlocking {
            store.clear()
            store.save(AuthSession("a0", "r0", AuthUser("7", "lars")))
        }
        return store
    }

    private fun client(store: TokenStore): RatatoskrClient =
        https.track(RatatoskrClientFactory.create(https.baseUrl, https.fingerprint, store))

    @Test
    fun aSessionCarryingRotatedTokensIsAdoptedAndPersisted() = runBlocking {
        val store = seededRealStore()
        https.enqueueJson(WireFixtures.sessionJson(rotatedTokens = "a2" to "r2"))

        val result = client(store).currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals("a2", store.currentAccessTokenBlocking())
        assertEquals("r2", store.refreshToken())
    }

    @Test
    fun stopSessionAdoptsARotatedPairFromA200Body() = runBlocking {
        val store = seededRealStore()
        https.enqueueJson(
            WireFixtures.sessionJson(state = "stopped", positionSeconds = 5.0, rotatedTokens = "a3" to "r3"),
        )

        val result = client(store).stopSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals("a3", store.currentAccessTokenBlocking())
        assertEquals("r3", store.refreshToken())
    }

    @Test
    fun stopSessionSucceedsOnA204AndKeepsTheStoredTokens() = runBlocking {
        val store = seededRealStore()
        https.enqueue204()

        val result = client(store).stopSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals("a0", store.currentAccessTokenBlocking())
        assertEquals("r0", store.refreshToken())
    }

    @Test
    fun startSessionHandsTheStoredRefreshTokenToTheServer() = runBlocking {
        val store = seededRealStore()
        https.enqueueJson(WireFixtures.sessionJson(positionSeconds = 0.0))

        val result = client(store).startSession("i1", "s1")

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        val body = https.takeRequest().body.readUtf8()
        assertTrue("request should carry the refresh token, was: $body", body.contains("\"r0\""))
    }
}
