/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What a banner reports. Semantic, not cosmetic: [ERROR] also announces itself to TalkBack,
 * because unlike a [NOTICE] it arrives mid-session and would otherwise be added to the screen in
 * silence.
 */
internal enum class BannerKind {
    /**
     * A heads-up about a state the user did not choose - an expired sign-in, or the one-time
     * re-login after an app update. Neither a success nor a failure the user caused, so it takes
     * the neutral surface rather than the error one.
     */
    NOTICE,

    /** An action or a load failed, reported where the action was. */
    ERROR,
}

/** Makes the whole banner tappable and appends [label] below the message as a second line. */
internal data class BannerAction(val label: String, val onClick: () -> Unit)

/** Where a banner sits, which decides how tight it is. */
internal enum class BannerPlacement { TopLevel, Nested }

/**
 * Set by any container that is itself a tonal surface, so a banner rendered inside one drops to
 * the nested metrics (ux-design: Patterns, "a nested surface sits tighter than its container").
 * Provided by the container, never by the call site: a call site says what it is reporting, not
 * how the surface should look.
 */
internal val LocalBannerPlacement = staticCompositionLocalOf { BannerPlacement.TopLevel }

/**
 * The app's one inline report surface: a tonal card with a leading glyph and a message, used
 * wherever the user has to be told something about the state they are in.
 *
 * Colour alone cannot carry the notice/error distinction - the light palette's `surfaceVariant`
 * and `errorContainer` are both warm tints - so each kind leads with its own glyph, and the glyph
 * is a redundant carrier rather than the only one.
 *
 * Metrics come from [LocalBannerPlacement], so the three screens that report errors do not each
 * decide their own radius and padding. [action] stays a parameter because whether a failure can
 * be retried in place is something only the call site knows.
 */
@Composable
internal fun InlineBanner(
    kind: BannerKind,
    text: String,
    modifier: Modifier = Modifier,
    action: BannerAction? = null,
) {
    val container = when (kind) {
        BannerKind.NOTICE -> MaterialTheme.colorScheme.surfaceVariant
        BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val glyph: ImageVector = when (kind) {
        BannerKind.NOTICE -> Icons.Outlined.Info
        BannerKind.ERROR -> Icons.Outlined.Warning
    }
    val shape = when (LocalBannerPlacement.current) {
        BannerPlacement.TopLevel -> MaterialTheme.shapes.large
        BannerPlacement.Nested -> MaterialTheme.shapes.medium
    }
    val contentPadding = when (LocalBannerPlacement.current) {
        BannerPlacement.TopLevel -> PaddingValues(16.dp)
        BannerPlacement.Nested -> PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    }
    // An error is added to the screen after the user acted, so it has to say so; a notice is
    // already there when the screen opens and is reached in normal traversal order.
    val announce = if (kind == BannerKind.ERROR) {
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    } else {
        Modifier
    }
    // A one-line message hangs the glyph off its first text line; an actionable banner is a block
    // of two lines and centres against it.
    val glyphAlignment = if (action == null) Alignment.Top else Alignment.CenterVertically

    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = glyphAlignment,
        ) {
            Icon(glyph, contentDescription = null)
            if (action == null) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    val surfaceModifier = modifier.fillMaxWidth().then(announce)
    if (action == null) {
        Surface(shape = shape, color = container, modifier = surfaceModifier, content = body)
    } else {
        // The Surface onClick overload rather than Modifier.clickable: it carries the button role
        // into the semantics tree, so the whole-row tap is discoverable non-visually too.
        Surface(
            onClick = action.onClick,
            shape = shape,
            color = container,
            modifier = surfaceModifier,
            content = body,
        )
    }
}

/**
 * The continue-listening shelf's tonal band. Owns the band tone and the nested placement
 * together, so nothing can land in the band with the band's background but a top-level banner's
 * metrics.
 *
 * [contentPadding] is applied inside the background on purpose: the band has to fill the gaps
 * between its rows, which it cannot do if the caller pads from the outside.
 */
@Composable
internal fun ShelfBand(
    contentPadding: PaddingValues = PaddingValues(),
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(
            LocalBannerPlacement provides BannerPlacement.Nested,
            content = content,
        )
    }
}
