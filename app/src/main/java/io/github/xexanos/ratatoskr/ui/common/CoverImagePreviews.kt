/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.intercept.Interceptor
import io.github.xexanos.ratatoskr.ui.LocalImmediateLoading
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import kotlinx.coroutines.awaitCancellation

// Screenshot goldens for the shared cover tile ([CoverImage]), kept in a sibling file so the
// production file stays the composable and nothing else. Roborazzi turns each @Preview into a
// golden (build.gradle.kts: generateComposePreviewRobolectricTests): drift in the knot mark, the
// spinner size, the tile tones, or the letterbox framing is caught pixel-for-pixel. Previews live
// in the main source set (not test/), because that is what the preview pane and the golden
// generator both scan.
//
// Every state is reached through public seams (ADR 0001):
//  - no cover -> the real public entry point, CoverImage(coverUrl = null).
//  - loading  -> the real CoverImage with a never-resolving loader through LocalCoverImageLoader,
//    plus LocalImmediateLoading so the spinner's 500 ms threshold opens in the static frame.
//  - loaded   -> a synthetic bitmap through an Image with the same Surface + ContentScale.Fit.
//    This one stays a stand-in: no loader delivers a success state onto the paused-clock frame
//    (verified against both a fake loader and coil's preview handler, issue #100), so the golden
//    proves the Fit treatment, not CoverImage's contentScale default.

private const val ROW_TILE_DP = 56 // list-row / shelf cover
private const val NOW_PLAYING_TILE_DP = 260 // now-playing cover

// A URL whose shape the cover pipeline accepts; the never-resolving loader ensures no request
// ever completes, so the host is never contacted.
private const val PREVIEW_COVER_URL = "https://preview.invalid/v1/library/items/preview/cover"

// The production framing: the tonal Surface CoverImage wraps every tile in, sized to one tile.
@Composable
private fun CoverTilePreview(dark: Boolean, sizeDp: Int, content: @Composable () -> Unit) {
    RatatoskrTheme(darkTheme = dark) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(sizeDp.dp),
        ) {
            content()
        }
    }
}

// The no-cover tile through its real caller, so NoCoverTile need not widen to internal.
@Composable
private fun NoCoverPreview(dark: Boolean, sizeDp: Int) {
    RatatoskrTheme(darkTheme = dark) {
        CoverImage(coverUrl = null, modifier = Modifier.size(sizeDp.dp))
    }
}

// The loading tile through its real caller: a loader whose every request hangs keeps CoverImage
// in its loading slot, and LocalImmediateLoading lets the spinner show in the captured frame.
@Composable
private fun LoadingPreview(dark: Boolean, sizeDp: Int) {
    val context = LocalPlatformContext.current
    val neverLoader = remember {
        ImageLoader.Builder(context).components { add(Interceptor { awaitCancellation() }) }.build()
    }
    RatatoskrTheme(darkTheme = dark) {
        CompositionLocalProvider(
            LocalCoverImageLoader provides neverLoader,
            LocalImmediateLoading provides true,
        ) {
            CoverImage(coverUrl = PREVIEW_COVER_URL, modifier = Modifier.size(sizeDp.dp))
        }
    }
}

@Preview(name = "No-cover 56dp light")
@Composable
internal fun NoCoverTile56LightPreview() = NoCoverPreview(dark = false, sizeDp = ROW_TILE_DP)

@Preview(name = "No-cover 56dp dark")
@Composable
internal fun NoCoverTile56DarkPreview() = NoCoverPreview(dark = true, sizeDp = ROW_TILE_DP)

@Preview(name = "No-cover 260dp light")
@Composable
internal fun NoCoverTile260LightPreview() = NoCoverPreview(dark = false, sizeDp = NOW_PLAYING_TILE_DP)

@Preview(name = "No-cover 260dp dark")
@Composable
internal fun NoCoverTile260DarkPreview() = NoCoverPreview(dark = true, sizeDp = NOW_PLAYING_TILE_DP)

@Preview(name = "Loading 56dp light")
@Composable
internal fun LoadingTile56LightPreview() = LoadingPreview(dark = false, sizeDp = ROW_TILE_DP)

@Preview(name = "Loading 56dp dark")
@Composable
internal fun LoadingTile56DarkPreview() = LoadingPreview(dark = true, sizeDp = ROW_TILE_DP)

@Preview(name = "Loading 260dp light")
@Composable
internal fun LoadingTile260LightPreview() = LoadingPreview(dark = false, sizeDp = NOW_PLAYING_TILE_DP)

@Preview(name = "Loading 260dp dark")
@Composable
internal fun LoadingTile260DarkPreview() = LoadingPreview(dark = true, sizeDp = NOW_PLAYING_TILE_DP)

// A stand-in cover: a solid fill with a centered disc, so any non-uniform scaling shows as an
// out-of-round disc. The two colors come from the theme (not literals - the UX gate forbids
// hardcoded Color(0x...), scripts/check-ux.sh); they only need to contrast with the tile's
// secondaryContainer band for the letterbox to read.
private fun sampleCoverBitmap(widthPx: Int, heightPx: Int, fill: Color, mark: Color): ImageBitmap {
    val bitmap = ImageBitmap(widthPx, heightPx)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply { color = fill }
    canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
    paint.color = mark
    canvas.drawCircle(Offset(widthPx / 2f, heightPx / 2f), minOf(widthPx, heightPx) / 3f, paint)
    return bitmap
}

// Goldens for the Fit treatment (CoverImage's KDoc) at row size, in the two aspect ratios: a
// portrait 2:3 cover must show tonal bands to the sides and a round disc; a square one must fill
// edge to edge.
@Composable
private fun SampleCoverTile(dark: Boolean, widthPx: Int, heightPx: Int) {
    CoverTilePreview(dark = dark, sizeDp = ROW_TILE_DP) {
        val fill = MaterialTheme.colorScheme.primary
        val mark = MaterialTheme.colorScheme.onPrimary
        val bitmap = remember(widthPx, heightPx, fill, mark) {
            sampleCoverBitmap(widthPx, heightPx, fill, mark)
        }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(name = "Cover portrait 56dp light")
@Composable
internal fun CoverPortrait56LightPreview() =
    SampleCoverTile(dark = false, widthPx = 40, heightPx = 60)

@Preview(name = "Cover portrait 56dp dark")
@Composable
internal fun CoverPortrait56DarkPreview() =
    SampleCoverTile(dark = true, widthPx = 40, heightPx = 60)

@Preview(name = "Cover square 56dp light")
@Composable
internal fun CoverSquare56LightPreview() =
    SampleCoverTile(dark = false, widthPx = 60, heightPx = 60)

@Preview(name = "Cover square 56dp dark")
@Composable
internal fun CoverSquare56DarkPreview() =
    SampleCoverTile(dark = true, widthPx = 60, heightPx = 60)
