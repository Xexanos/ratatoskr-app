/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.WireFixtures
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The minified-context mirror of the component layer (SPEC section 9): the component suite
 * lives in `core-network` and R8 only runs at the app level, so those tests can never
 * execute against shrunk output. This suite re-drives the two wire-level behaviours most
 * exposed to R8 - the Moshi codegen adapters being present (they are looked up reflectively)
 * and the unknown-enum fallback - through `RatatoskrClientFactory.create(...)` inside the
 * app process. Meaningful when run on the `minified` build type (`-PminifiedTests`,
 * post-merge workflow); on debug it is a thin, harmless duplicate of the component suite.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedWireSmokeTest {

    @get:Rule val https = HttpsMockServer()

    private fun client(): RatatoskrClient =
        https.track(RatatoskrClientFactory.create(https.baseUrl, https.fingerprint, FakeTokenAccess("a0", "r0")))

    @Test
    fun sessionDeserializesThroughTheShrunkAdapters() = runBlocking {
        https.enqueueJson(WireFixtures.sessionJson(state = "paused", positionSeconds = 3.0))

        val result = client().currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals(PlaybackState.PAUSED, (result as ApiResult.Success).data.state)
    }

    @Test
    fun unknownPlaybackStateStillDegradesToStopped() = runBlocking {
        https.enqueueJson(WireFixtures.sessionJson(state = "warping"))

        val result = client().currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals(PlaybackState.STOPPED, (result as ApiResult.Success).data.state)
    }

    @Test
    fun unknownJsonFieldsAreStillTolerated() = runBlocking {
        https.enqueueJson(WireFixtures.sessionJson(extraJson = """"someFutureField":123"""))

        val result = client().currentSession()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertEquals("i1", (result as ApiResult.Success).data.itemId)
    }
}
