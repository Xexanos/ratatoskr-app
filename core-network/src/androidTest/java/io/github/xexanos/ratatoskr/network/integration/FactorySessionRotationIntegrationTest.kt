/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.integration

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import io.github.xexanos.ratatoskr.network.persist.KeystoreCrypto
import io.github.xexanos.ratatoskr.network.persist.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
class FactorySessionRotationIntegrationTest {

    private val https = HttpsMockServer()
    private val created = mutableListOf<RatatoskrClient>()

    @get:Rule val testName = TestName()

    @Before fun setUp() = https.start()

    @After fun tearDown() {
        created.forEach { it.close() }
        https.shutdown()
    }

    /** A real Keystore-backed store, isolated per test (unique DataStore file + key alias). */
    private fun seededRealStore(): TokenStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStore = PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("ratatoskr_test_${testName.methodName}")
        }
        val store = TokenStore(dataStore, KeystoreCrypto(keyAlias = "ratatoskr.test.${testName.methodName}"))
        runBlocking {
            store.clear()
            store.save(AuthSession("a0", "r0", AuthUser("7", "lars")))
        }
        return store
    }

    private fun client(store: TokenStore): RatatoskrClient =
        RatatoskrClientFactory.create(https.baseUrl, https.fingerprint, store)
            .also { created += it }

    @Test
    fun aSessionCarryingRotatedTokensIsAdoptedAndPersisted() = runBlocking {
        val store = seededRealStore()
        https.enqueueJson(
            """
            {"itemId":"i1","speakerId":"s1","state":"playing","positionSeconds":1.0,
             "durationSeconds":10.0,"updatedAt":"2026-07-05T12:00:00Z",
             "rotatedTokens":{"accessToken":"a2","refreshToken":"r2"}}
            """,
        )

        val result = client(store).currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals("a2", store.currentAccessTokenBlocking())
        assertEquals("r2", store.refreshToken())
    }

    @Test
    fun stopSessionAdoptsARotatedPairFromA200Body() = runBlocking {
        val store = seededRealStore()
        https.enqueueJson(
            """
            {"itemId":"i1","speakerId":"s1","state":"stopped","positionSeconds":5.0,
             "durationSeconds":10.0,"updatedAt":"2026-07-05T12:00:00Z",
             "rotatedTokens":{"accessToken":"a3","refreshToken":"r3"}}
            """,
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
        https.enqueueJson(
            """
            {"itemId":"i1","speakerId":"s1","state":"playing","positionSeconds":0.0,
             "durationSeconds":10.0,"updatedAt":"2026-07-05T12:00:00Z"}
            """,
        )

        val result = client(store).startSession("i1", "s1")

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        val body = https.takeRequest().body.readUtf8()
        assertTrue("request should carry the refresh token, was: $body", body.contains("\"r0\""))
    }
}
