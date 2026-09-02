/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * How emphatic a [ChipLeading.Dot] is. Deliberately not a colour: the chip owns which tones the
 * two mean, so no call site can tint a dot off the palette.
 */
internal enum class ChipDot {
    /** The one state worth the brand's copper: something is actually happening. */
    ACCENT,

    /** Everything else - the dot takes the label's own ink and stays part of the sentence. */
    MUTED,
}

// The dot is emphasis on top of a carrier, never the carrier: the two tones sit at nearly the
// same lightness in dark (1.3:1) and separate by hue alone, so the label has to name the state in
// words - which it does, and which is also all TalkBack reads (ux-design: Patterns).

/**
 * What leads a chip's label: exactly one of the two, never both and never neither. The design's
 * unbuilt Speakers "Last used" chip is a third case with no leading at all; it gets a variant when
 * it is built, not before.
 */
internal sealed interface ChipLeading {
    /** A glyph, for a chip stating a fact about the world - the server whose certificate we hold. */
    data class Glyph(val icon: ImageVector) : ChipLeading

    /** A dot, for a chip stating which of several states one thing is currently in. */
    data class Dot(val emphasis: ChipDot) : ChipLeading
}

// The pill's own metrics, the reason this component exists: two screens assembled them separately
// and drifted apart on tone, shape and label size while a code comment claimed they matched.
private val CONTENT_PADDING_HORIZONTAL = 12.dp
private val CONTENT_PADDING_VERTICAL = 6.dp
private val LEADING_GAP = 8.dp
private val GLYPH_SIZE = 16.dp
private val DOT_SIZE = 8.dp

/**
 * The **chip** status surface: a pill on the secondary tone carrying one short, settled fact -
 * which server the app trusts, which state playback is in. Never interactive and never a filter;
 * a tappable pill would be a button, which is a different component and deliberately not this one.
 *
 * Ash green is the tone the design reserves for status (ux-design: Principles, P3), and taking it
 * also keeps a chip off `surfaceVariant`, which Patterns has already given to the notice banner -
 * a status chip on the notice tone would read as a muted notice.
 *
 * Tone, shape, metrics and label typography all live here rather than at the call site
 * (ux-design: Patterns). The call site says what it is stating and whether a glyph or a dot leads
 * it; it cannot say how the pill looks.
 */
@Composable
internal fun StatusChip(
    label: String,
    leading: ChipLeading,
    modifier: Modifier = Modifier,
) {
    Surface(
        // Full pill - the design's chip shape (ux-design: Shape tokens).
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        // Explicit rather than left to contentColorFor: the tone *pair* is what this component
        // owns, and the leading dot's muted emphasis is read back off it below.
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = CONTENT_PADDING_HORIZONTAL,
                vertical = CONTENT_PADDING_VERTICAL,
            ),
            horizontalArrangement = Arrangement.spacedBy(LEADING_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (leading) {
                is ChipLeading.Glyph -> Icon(
                    leading.icon,
                    // Decorative: the label beside it already says what the chip states, and a
                    // second reading of the same fact only slows TalkBack down.
                    contentDescription = null,
                    modifier = Modifier.size(GLYPH_SIZE),
                )

                is ChipLeading.Dot -> Box(
                    Modifier.size(DOT_SIZE).background(dotColor(leading.emphasis), CircleShape),
                )
            }
            Text(
                label,
                // 12 sp at 600 - the label role the typography table gives chips. It is also the
                // size at which the longest label a chip can carry, a German locale with a port in
                // the host, still fits the pill on one line.
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MUTED reads the surface's own content colour rather than naming a role: whatever ink the label
// is set in, a muted dot is that ink, so the two cannot drift apart.
@Composable
private fun dotColor(emphasis: ChipDot): Color = when (emphasis) {
    ChipDot.ACCENT -> MaterialTheme.colorScheme.primary
    ChipDot.MUTED -> LocalContentColor.current
}
