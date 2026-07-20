/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.network.HttpException
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.xexanos.ratatoskr.ui.KnotMark
import io.github.xexanos.ratatoskr.ui.UiTestTags
import io.github.xexanos.ratatoskr.ui.rememberDelayedVisible
import io.github.xexanos.ratatoskr.ui.theme.LocalReducedMotion
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

/**
 * The cover [ImageLoader], provided by the activity from the AppContainer. A CompositionLocal
 * instead of Coil's process-wide singleton so UI tests can swap in a FakeImageLoaderEngine
 * per test, and so the loader's lifecycle stays visible in the container (SPEC section 12).
 */
val LocalCoverImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("No cover ImageLoader provided")
}

/**
 * The one cover-art tile every surface renders (library rows, the continue-listening shelf,
 * now-playing): the server-scaled cover when it loads, and two quiet tiles before and instead
 * of it (ux-design: placeholders, decided in issue #78):
 *
 * - the loading tile: the bare tonal surface, plus a small spinner once a load has been in
 *   flight long enough to matter (the KnotLoader delay convention; never under reduced motion).
 *   A load that fails for any reason other than "no cover" (timeout, server error) falls back
 *   to this bare tile: claiming "no cover" for a book that may have one would be a lie, and
 *   the bare surface still looks finished, not broken.
 * - the no-cover tile: the knot mark tinted onSecondaryContainer, shown when the book has no
 *   cover - a null [coverUrl] (the contract's documented "no cover", rendered without ever
 *   issuing a request) or a 404 from the cover proxy. The same mark for every coverless book:
 *   repeated identical marks read as a deliberate pattern, where repeated title initials read
 *   as a bug in an alphabetical list.
 *
 * The image is decorative: title and author are adjacent text on every surface, so a content
 * description would only make TalkBack read everything twice.
 *
 * No size parameter: the composable fills whatever [modifier] sizes it to, and the bucketed
 * `?h=` follows from that measured height via [CoverHeightInterceptor] - callers cannot
 * introduce drifting size constants.
 */
@Composable
fun CoverImage(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    // 8 dp - the design's cover-thumbnail radius (ux-design: Shape tokens), shapes.small here.
    shape: Shape = MaterialTheme.shapes.small,
    shadowElevation: Dp = 0.dp,
    tonalElevation: Dp = 0.dp,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = shadowElevation,
        tonalElevation = tonalElevation,
    ) {
        if (coverUrl == null) {
            NoCoverTile()
        } else {
            // Per-request crossfade instead of the loader-wide default so "remove animations"
            // is honored: the wait is already communicated by the loading tile, so under
            // reduced motion the cover may simply appear (same convention as the knot loader).
            val reducedMotion = LocalReducedMotion.current
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(coverUrl)
                    .crossfade(!reducedMotion)
                    .build(),
                imageLoader = LocalCoverImageLoader.current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { LoadingTile(withSpinner = !reducedMotion) },
                error = { state ->
                    val isNoCover = (state.result.throwable as? HttpException)?.response?.code == 404
                    if (isNoCover) NoCoverTile() else LoadingTile(withSpinner = false)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LoadingTile(withSpinner: Boolean) {
    // The spinner earns its place only on genuinely slow loads: the delay keeps normal
    // scrolling free of flicker, and a failed load passes withSpinner = false - a spinner
    // that keeps turning over a dead request would claim work that isn't happening.
    LoadingTileContent(showSpinner = withSpinner && rememberDelayedVisible(active = true))
}

// The visible layout of the loading tile, split from the delay gate so the previews (and any
// screenshot golden) can render the spinner-shown state directly - rememberDelayedVisible never
// elapses in a static render, so a preview of LoadingTile would always be the bare tile.
@Composable
private fun LoadingTileContent(showSpinner: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().testTag(UiTestTags.COVER_LOADING),
    ) {
        if (showSpinner) CoverSpinner()
    }
}

@Composable
private fun CoverSpinner() {
    CircularProgressIndicator(
        // Proportional on the 56 dp row tile (~20 dp) but capped so the 260 dp now-playing tile
        // still gets a small spinner, not a 91 dp ring. Cleared semantics: the tile is
        // decorative (title/author are adjacent text), and the indicator's built-in
        // progressSemantics would otherwise leak "in progress" into every row announcement -
        // one announcement, not two (KnotLoader rule).
        modifier = Modifier
            .fillMaxSize(0.35f)
            .sizeIn(maxWidth = 40.dp, maxHeight = 40.dp)
            .clearAndSetSemantics {},
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
private fun NoCoverTile() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().testTag(UiTestTags.COVER_NO_COVER),
    ) {
        KnotMark(
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            // ~60% of the tile: enough air around the mark that it reads as a stamp on the
            // surface, not artwork trying to fill it (ux-design: placeholders).
            modifier = Modifier.fillMaxSize(0.6f),
        )
    }
}

// --- Previews / screenshot goldens ---------------------------------------------------------
//
// Both placeholder tiles at both sizes they ship at - the 56 dp list-row thumbnail and the
// 260 dp now-playing tile - in light and dark. These render without a server or ImageLoader
// (the coverUrl != null path needs one; the tiles do not), so Roborazzi turns each @Preview
// into a screenshot golden (build.gradle.kts: generateComposePreviewRobolectricTests): drift
// in the knot mark, the spinner size, or the tile tones is caught pixel-for-pixel. The loading
// previews force the spinner visible via LoadingTileContent - the delayed gate never elapses
// in a static render.

private const val ROW_TILE_DP = 56 // list-row / shelf thumbnail
private const val NOW_PLAYING_TILE_DP = 260 // now-playing cover

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

@Preview(name = "No-cover 56dp light")
@Composable
internal fun NoCoverTile56LightPreview() =
    CoverTilePreview(dark = false, sizeDp = ROW_TILE_DP) { NoCoverTile() }

@Preview(name = "No-cover 56dp dark")
@Composable
internal fun NoCoverTile56DarkPreview() =
    CoverTilePreview(dark = true, sizeDp = ROW_TILE_DP) { NoCoverTile() }

@Preview(name = "No-cover 260dp light")
@Composable
internal fun NoCoverTile260LightPreview() =
    CoverTilePreview(dark = false, sizeDp = NOW_PLAYING_TILE_DP) { NoCoverTile() }

@Preview(name = "No-cover 260dp dark")
@Composable
internal fun NoCoverTile260DarkPreview() =
    CoverTilePreview(dark = true, sizeDp = NOW_PLAYING_TILE_DP) { NoCoverTile() }

@Preview(name = "Loading 56dp light")
@Composable
internal fun LoadingTile56LightPreview() =
    CoverTilePreview(dark = false, sizeDp = ROW_TILE_DP) { LoadingTileContent(showSpinner = true) }

@Preview(name = "Loading 56dp dark")
@Composable
internal fun LoadingTile56DarkPreview() =
    CoverTilePreview(dark = true, sizeDp = ROW_TILE_DP) { LoadingTileContent(showSpinner = true) }

@Preview(name = "Loading 260dp light")
@Composable
internal fun LoadingTile260LightPreview() =
    CoverTilePreview(dark = false, sizeDp = NOW_PLAYING_TILE_DP) { LoadingTileContent(showSpinner = true) }

@Preview(name = "Loading 260dp dark")
@Composable
internal fun LoadingTile260DarkPreview() =
    CoverTilePreview(dark = true, sizeDp = NOW_PLAYING_TILE_DP) { LoadingTileContent(showSpinner = true) }
