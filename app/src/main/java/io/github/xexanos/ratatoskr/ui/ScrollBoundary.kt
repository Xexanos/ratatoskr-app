/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The hairline closing a scroll region that continues behind a bottom-pinned action
 * (ux-design: Patterns, "Pinned actions"). Without it a form sliced mid-field reads as a form
 * that simply ends there, because a pinned action - unlike one at the end of the scroller -
 * gives the eye no reason to expect anything behind it.
 *
 * A component rather than four lines at each call site, for the reason the nesting rule gives:
 * a rule that binds every screen pinning an action is carried in code by the component, never by
 * the call site. Sign in and Connect (issue #165) are two screens, which is where "both grew the
 * same block independently" stops being hypothetical.
 *
 * The lane is reserved whether or not the line is drawn, so the action never shifts by the
 * hairline's own height as it comes and goes. Full-bleed by default: it marks the container's
 * edge, not the inset content's.
 *
 * [scrollState] must be the one driving the region above, and that region's trailing padding has
 * to sit *outside* its `verticalScroll` - padding inside the scroll is content, so the line would
 * still promise more when only whitespace is left.
 */
@Composable
internal fun ScrollBoundary(scrollState: ScrollState, modifier: Modifier = Modifier) {
    // canScrollForward is computed from the raw scroll offset, so a direct read would recompose
    // the caller on every scrolled pixel, where the boundary changes only when it appears or goes.
    val continuesBelow by remember(scrollState) { derivedStateOf { scrollState.canScrollForward } }

    Box(modifier.fillMaxWidth().height(DividerDefaults.Thickness)) {
        if (continuesBelow) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.testTag(UiTestTags.SCROLL_BOUNDARY),
            )
        }
    }
}
