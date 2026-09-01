/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Screenshot goldens for [InlineBanner], kept in a sibling file so the production file stays the
// composable and nothing else. These pin the component directly rather than incidentally through
// the six screen goldens that happen to contain a banner: the notice/error tones, the glyph pair,
// and the tighter nested metrics are all caught here first.
//
// Both themes, because the error ramp is derived differently per theme - light is the brand's
// own, dark is Material 3's baseline pinned deliberately (theme/Color.kt). A dark golden is how
// "the dark values did not move" stops being an assertion.
//
// There is no NOTICE/Nested golden: nothing in the app puts a notice inside the shelf band, and a
// golden for it would freeze a state that cannot be reached.

@Composable
private fun TopLevelBannerPreview(dark: Boolean, content: @Composable () -> Unit) {
    RatatoskrTheme(darkTheme = dark) {
        Surface { Box(Modifier.padding(16.dp)) { content() } }
    }
}

// Provides the nesting itself rather than borrowing the library screen's ShelfBand: what this
// golden pins is the banner's own metrics, and the band tone around it is only a frame so the
// surrounding surface is visible at all.
@Composable
private fun NestedBannerPreview(dark: Boolean, content: @Composable () -> Unit) {
    RatatoskrTheme(darkTheme = dark) {
        Surface {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                CompositionLocalProvider(
                    LocalBannerPlacement provides BannerPlacement.Nested,
                    content = content,
                )
            }
        }
    }
}

@Preview(name = "Banner - notice light", widthDp = 360)
@Composable
internal fun BannerNoticeLightPreview() = TopLevelBannerPreview(dark = false) {
    InlineBanner(
        kind = BannerKind.NOTICE,
        text = stringResource(R.string.signin_notice_session_ended),
    )
}

@Preview(name = "Banner - notice dark", widthDp = 360)
@Composable
internal fun BannerNoticeDarkPreview() = TopLevelBannerPreview(dark = true) {
    InlineBanner(
        kind = BannerKind.NOTICE,
        text = stringResource(R.string.signin_notice_session_ended),
    )
}

@Preview(name = "Banner - error light", widthDp = 360)
@Composable
internal fun BannerErrorLightPreview() = TopLevelBannerPreview(dark = false) {
    InlineBanner(kind = BannerKind.ERROR, text = stringResource(R.string.error_wrong_credentials))
}

@Preview(name = "Banner - error dark", widthDp = 360)
@Composable
internal fun BannerErrorDarkPreview() = TopLevelBannerPreview(dark = true) {
    InlineBanner(kind = BannerKind.ERROR, text = stringResource(R.string.error_wrong_credentials))
}

@Preview(name = "Banner - error nested light", widthDp = 360)
@Composable
internal fun BannerErrorNestedLightPreview() = NestedBannerPreview(dark = false) {
    InlineBanner(
        kind = BannerKind.ERROR,
        text = stringResource(R.string.library_shelf_error_title),
        action = BannerAction(label = stringResource(R.string.library_shelf_error_retry), onClick = {}),
    )
}

@Preview(name = "Banner - error nested dark", widthDp = 360)
@Composable
internal fun BannerErrorNestedDarkPreview() = NestedBannerPreview(dark = true) {
    InlineBanner(
        kind = BannerKind.ERROR,
        text = stringResource(R.string.library_shelf_error_title),
        action = BannerAction(label = stringResource(R.string.library_shelf_error_retry), onClick = {}),
    )
}
