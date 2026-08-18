/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.auth

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Previews / screenshot goldens for the sign-in screen (render in Android Studio without a
// running server), driving the public [SignInScreen] off a fixed state (ADR 0001).

@Preview(name = "Sign in - idle", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInIdlePreview() = RatatoskrTheme {
    Surface { SignInScreen(SignInUiState.Idle) { _, _ -> } }
}

@Preview(name = "Sign in - error", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInErrorPreview() = RatatoskrTheme {
    Surface { SignInScreen(SignInUiState.Error(UiError.Domain(RatatoskrError.Unauthorized()))) { _, _ -> } }
}

@Preview(name = "Sign in - submitting", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInSubmittingPreview() = RatatoskrTheme {
    Surface { SignInScreen(SignInUiState.Submitting) { _, _ -> } }
}

// The 401 re-authentication path (SPEC section 5): pre-filled username, an explanatory notice, and
// a blank password.
@Preview(name = "Sign in - reauth notice", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInReauthNoticePreview() = RatatoskrTheme {
    Surface {
        SignInScreen(
            state = SignInUiState.Idle,
            initialUsername = "alex",
            notice = SignInNotice.MEDIA_SERVER_EXPIRED,
        ) { _, _ -> }
    }
}

// The one-time /v1 -> /v2 re-login (SPEC section 5): same pre-filled screen, the update notice.
@Preview(name = "Sign in - app-updated notice", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInAppUpdatedNoticePreview() = RatatoskrTheme {
    Surface {
        SignInScreen(
            state = SignInUiState.Idle,
            initialUsername = "alex",
            notice = SignInNotice.APP_UPDATED,
        ) { _, _ -> }
    }
}
