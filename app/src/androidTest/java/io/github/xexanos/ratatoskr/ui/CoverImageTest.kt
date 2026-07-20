/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ColorImage
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.test.FakeImageLoaderEngine
import io.github.xexanos.ratatoskr.ui.common.CoverImage
import io.github.xexanos.ratatoskr.ui.common.LocalCoverImageLoader
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import okhttp3.Call
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The three visual states of the shared [CoverImage] tile, with a [FakeImageLoaderEngine] (or a
 * deliberately failing loader) instead of a network: success replaces the initials tile, error
 * keeps it, a null URL renders it without a loader round-trip. The real network path - auth
 * header, `?h=` bucket, TOFU stack - is the integration flow's job (AppFlowTest).
 */
@RunWith(AndroidJUnit4::class)
class CoverImageTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val coverUrl = "https://server.example/v1/library/items/i1/cover"

    private fun setCover(loader: ImageLoader, url: String?) {
        compose.setContent {
            RatatoskrTheme {
                CompositionLocalProvider(LocalCoverImageLoader provides loader) {
                    CoverImage(title = "The Hobbit", coverUrl = url, modifier = Modifier.size(56.dp))
                }
            }
        }
    }

    @Test
    fun loadedCoverReplacesTheInitialsTile() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it is String && it.startsWith(coverUrl) }, ColorImage(Color.Red.toArgb()))
            .build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()

        setCover(loader, coverUrl)

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UiTestTags.COVER_PLACEHOLDER).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun failedLoadKeepsTheInitialsTile() {
        // A loader whose every call fails - the shape of "no cover" (404), "no server yet",
        // or a network error. The tile must simply stay; no third visual state.
        val loader = ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { Call.Factory { throw IOException("no covers here") } },
                    ),
                )
            }
            .build()

        setCover(loader, coverUrl)

        compose.waitForIdle()
        compose.onNodeWithTag(UiTestTags.COVER_PLACEHOLDER).assertExists()
        compose.onNodeWithText("T").assertExists()
    }

    @Test
    fun nullCoverUrlRendersTheTileWithoutAnyRequest() {
        // An engine with no handlers: any request reaching it would fail the test through
        // Coil's error path; the null branch must never build a request at all.
        val engine = FakeImageLoaderEngine.Builder().build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()

        setCover(loader, null)

        compose.onNodeWithTag(UiTestTags.COVER_PLACEHOLDER).assertExists()
        compose.onNodeWithText("T").assertExists()
    }
}
