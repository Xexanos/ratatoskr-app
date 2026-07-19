/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import io.github.xexanos.ratatoskr.ui.UiTestTags

/**
 * The cover [ImageLoader], provided by the activity from the AppContainer. A CompositionLocal
 * instead of Coil's process-wide singleton so UI tests can swap in a FakeImageLoaderEngine
 * per test, and so the loader's lifecycle stays visible in the container (SPEC section 12).
 */
val LocalCoverImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("No cover ImageLoader provided")
}

/**
 * The one cover-art tile every surface renders (library rows today, the continue-listening
 * shelf of #52 tomorrow, now-playing): the server-scaled cover when it loads, and the title's
 * initial on a tinted tile before and instead of it. Placeholder and error state are
 * deliberately identical - "no cover" (a 404 from the proxy) must look finished, not broken,
 * so a failed load simply keeps the tile (ux-design: placeholders).
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
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    initialStyle: TextStyle = MaterialTheme.typography.titleLarge,
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
            // The server always sends a cover URL today; null is the contract's documented
            // "no cover" - render the tile without ever issuing a request.
            InitialTile(title, initialStyle)
        } else {
            SubcomposeAsyncImage(
                model = coverUrl,
                imageLoader = LocalCoverImageLoader.current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { InitialTile(title, initialStyle) },
                error = { InitialTile(title, initialStyle) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun InitialTile(title: String, initialStyle: TextStyle) {
    val initial = title.trim().firstOrNull()?.uppercase()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().testTag(UiTestTags.COVER_PLACEHOLDER),
    ) {
        if (initial != null) {
            Text(
                text = initial,
                style = initialStyle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
