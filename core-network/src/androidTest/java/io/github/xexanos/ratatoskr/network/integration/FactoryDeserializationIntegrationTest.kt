/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 9, bullet (a): response deserialization through the converter the FACTORY
 * builds, not a test-wired Moshi. This is the exact regression the section calls out - the
 * unknown-`PlaybackState` fallback was attached to the client but not to the factory's
 * Retrofit converter, so it never ran on real responses while a hand-wired unit test stayed
 * green. Driving `RatatoskrClientFactory.create` over HTTPS is what closes that gap.
 */
@RunWith(AndroidJUnit4::class)
class FactoryDeserializationIntegrationTest {

    @get:Rule val https = HttpsMockServer()

    private fun client(): RatatoskrClient =
        https.track(RatatoskrClientFactory.create(https.baseUrl, https.fingerprint, FakeTokenAccess("a0", "r0")))

    @Test
    fun unknownPlaybackStateDegradesToStoppedThroughTheFactoryConverter() = runBlocking {
        https.enqueueJson(WireFixtures.sessionJson(state = "warping"))

        val result = client().currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals(PlaybackState.STOPPED, (result as ApiResult.Success).data.state)
    }

    @Test
    fun unknownJsonFieldsAreTolerated() = runBlocking {
        https.enqueueJson(WireFixtures.sessionJson(extraJson = """"someFutureField":123"""))

        val result = client().currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        val session = (result as ApiResult.Success).data
        assertEquals("i1", session.itemId)
        assertEquals(PlaybackState.PLAYING, session.state)
    }

    @Test
    fun aKnownStateDeserializesNormally() = runBlocking {
        https.enqueueJson(WireFixtures.sessionJson(state = "paused", positionSeconds = 3.0))

        val result = client().currentSession()

        assertEquals(PlaybackState.PAUSED, (result as ApiResult.Success).data.state)
    }
}
