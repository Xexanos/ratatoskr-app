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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ColorImage
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkResponse
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
 * The visual states of the shared [CoverImage] tile, with a [FakeImageLoaderEngine] (or a
 * deliberately failing loader) instead of a network: success replaces the loading tile, a 404
 * shows the no-cover knot tile, any other failure keeps the bare loading tile (never claiming
 * "no cover" for a book that may have one), and a null URL shows the no-cover tile without a
 * loader round-trip (ux-design: placeholders, issue #78). The real network path - auth header,
 * `?h=` bucket, TOFU stack - is the integration flow's job (AppFlowTest).
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
                    CoverImage(coverUrl = url, modifier = Modifier.size(56.dp))
                }
            }
        }
    }

    @Test
    fun loadedCoverReplacesTheLoadingTile() {
        val engine = FakeImageLoaderEngine.Builder()
            .intercept({ it is String && it.startsWith(coverUrl) }, ColorImage(Color.Red.toArgb()))
            .build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()

        setCover(loader, coverUrl)

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UiTestTags.COVER_LOADING).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(UiTestTags.COVER_NO_COVER).assertDoesNotExist()
    }

    @Test
    fun notFoundShowsTheNoCoverTile() {
        // A 404 from the cover proxy is the contract's "this book has no cover" - the knot
        // tile, not an error look.
        val loader = ImageLoader.Builder(context)
            .components {
                add(
                    Interceptor { _ ->
                        throw HttpException(NetworkResponse(code = 404))
                    },
                )
            }
            .build()

        setCover(loader, coverUrl)

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(UiTestTags.COVER_NO_COVER).fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun transientFailureKeepsTheLoadingTile() {
        // A loader whose every call fails below HTTP - the shape of "no server yet" or a
        // network error. The cover may well exist, so the tile must not claim "no cover":
        // the bare loading tile simply stays.
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
        compose.onNodeWithTag(UiTestTags.COVER_LOADING).assertExists()
        compose.onNodeWithTag(UiTestTags.COVER_NO_COVER).assertDoesNotExist()
    }

    @Test
    fun nullCoverUrlShowsTheNoCoverTileWithoutAnyRequest() {
        // An engine with no handlers: any request reaching it would fail the test through
        // Coil's error path; the null branch must never build a request at all.
        val engine = FakeImageLoaderEngine.Builder().build()
        val loader = ImageLoader.Builder(context).components { add(engine) }.build()

        setCover(loader, null)

        compose.onNodeWithTag(UiTestTags.COVER_NO_COVER).assertExists()
        compose.onNodeWithTag(UiTestTags.COVER_LOADING).assertDoesNotExist()
    }
}
