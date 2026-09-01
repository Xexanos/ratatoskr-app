/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xexanos.ratatoskr.R

/**
 * Centered empty-state placeholder: a decorative icon above a title and supporting body.
 *
 * [onRetry] is optional, because not every emptiness is a failure: an empty library or a search
 * with no matches has nothing to retry, while "no speakers found" mirrors the state of the
 * network and the server and does (ux-design, EMPTY: "plus a retry").
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) = CenteredState(
    icon = icon,
    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
    title = title,
    body = body,
    onRetry = onRetry,
    modifier = modifier,
)

/**
 * The full-screen counterpart of [InlineBanner]: a screen that has nothing to show because its
 * load failed, which stays a centred message with its own retry rather than a tonal card
 * (deliberately outside the banner component - see its KDoc). [title] names whose state it
 * mirrors ("Couldn't load speakers"), [body] carries the reason from [UiError.text].
 *
 * [onRetry] is required, not nullable - "Retry is always the first offered action" (ux-design,
 * ERR), so a caller cannot render this without offering one.
 */
@Composable
fun ErrorState(
    title: String,
    body: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) = CenteredState(
    // The same warning glyph the inline banners lead with (issue #135), so a failure reads as
    // one wherever it is reported - and so the error role is never the only thing carrying it.
    icon = Icons.Outlined.Warning,
    iconTint = MaterialTheme.colorScheme.error,
    title = title,
    body = body,
    onRetry = onRetry,
    modifier = modifier,
)

/**
 * The shared layout behind [EmptyState] and [ErrorState].
 *
 * The icon is decorative (`contentDescription = null`), so it stays out of the semantics tree -
 * TalkBack skips it and the title/body carry the meaning. Shared so every one of these states
 * keeps that a11y idiom and the same layout in one place, instead of being copy-pasted (and
 * drifting) per screen.
 */
@Composable
private fun CenteredState(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = iconTint,
            )
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}
