/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.auth

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.ui.UiError
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme

// Previews / screenshot goldens for the sign-in screen (render in Android Studio without a
// running server), driving the public [SignInScreen] off a fixed state (ADR 0001).

// Every visit to sign-in has a trusted server behind it - launch routing only lands here once one
// exists, and the 401 path clears the token while keeping it - so every preview carries a host and
// shows the trust chip. The chip's absence is a defensive case with no screen of its own; the
// screen tests own it.
private const val PREVIEW_HOST = "ratatoskr.home.arpa"

// The longest label the chip can be asked to carry: a self-hosted server on a non-default port,
// in the locale whose word for "trusted" is the longer one.
private const val PREVIEW_HOST_WITH_PORT = "ratatoskr.home.arpa:8443"

@Preview(name = "Sign in - idle", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInIdlePreview() = RatatoskrTheme {
    Surface { SignInScreen(SignInUiState.Idle, serverHost = PREVIEW_HOST) { _, _ -> } }
}

@Preview(name = "Sign in - error", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInErrorPreview() = RatatoskrTheme {
    Surface {
        SignInScreen(SignInUiState.Error(UiError.WrongCredentials), serverHost = PREVIEW_HOST) { _, _ -> }
    }
}

@Preview(name = "Sign in - submitting", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInSubmittingPreview() = RatatoskrTheme {
    Surface { SignInScreen(SignInUiState.Submitting, serverHost = PREVIEW_HOST) { _, _ -> } }
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
            serverHost = PREVIEW_HOST,
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
            serverHost = PREVIEW_HOST,
        ) { _, _ -> }
    }
}

// Notice and error on one screen (a 401 return followed by a failed re-login), in both themes:
// the two cards must read as different kinds of message, not two shades of the same card.
@Composable
private fun NoticeVsErrorPreview(dark: Boolean) = RatatoskrTheme(darkTheme = dark) {
    Surface {
        SignInScreen(
            state = SignInUiState.Error(UiError.WrongCredentials),
            initialUsername = "alex",
            notice = SignInNotice.SESSION_ENDED,
            serverHost = PREVIEW_HOST,
        ) { _, _ -> }
    }
}

@Preview(name = "Sign in - notice vs error light", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInNoticeVsErrorLightPreview() = NoticeVsErrorPreview(dark = false)

@Preview(name = "Sign in - notice vs error dark", widthDp = 360, heightDp = 800)
@Composable
internal fun SignInNoticeVsErrorDarkPreview() = NoticeVsErrorPreview(dark = true)

// The two cases the 800 dp previews above cannot show, both of which the pinned action and the
// chip depend on.

// Short enough that the form genuinely has to scroll behind the pinned button. The failure is
// brought into view as it appears (issue #154), which scrolls the form to its end - so this is
// the frame that shows the error reachable, and by the same token the one where the boundary is
// correctly absent: there is nothing left behind the action.
@Preview(name = "Sign in - scrolled", widthDp = 360, heightDp = 600)
@Composable
internal fun SignInScrolledPreview() = RatatoskrTheme {
    Surface {
        SignInScreen(
            state = SignInUiState.Error(UiError.WrongCredentials),
            initialUsername = "alex",
            notice = SignInNotice.SESSION_ENDED,
            serverHost = PREVIEW_HOST,
        ) { _, _ -> }
    }
}

// The same short viewport with nothing scrolling it: the form overflows and the boundary hairline
// closes it. Both themes, because the hairline is the one element here carrying meaning on a
// single low-contrast tone, and it is drawn from a role that differs between them.
@Composable
private fun ShortViewportPreview(dark: Boolean) = RatatoskrTheme(darkTheme = dark) {
    Surface {
        SignInScreen(
            state = SignInUiState.Idle,
            initialUsername = "alex",
            notice = SignInNotice.SESSION_ENDED,
            serverHost = PREVIEW_HOST,
        ) { _, _ -> }
    }
}

@Preview(name = "Sign in - boundary light", widthDp = 360, heightDp = 600)
@Composable
internal fun SignInBoundaryLightPreview() = ShortViewportPreview(dark = false)

@Preview(name = "Sign in - boundary dark", widthDp = 360, heightDp = 600)
@Composable
internal fun SignInBoundaryDarkPreview() = ShortViewportPreview(dark = true)

// The chip's worst case for width (ux-design: "layouts survive +30% text"): the German label and
// a host that carries its port.
@Preview(name = "Sign in - long host, de", widthDp = 360, heightDp = 800, locale = "de")
@Composable
internal fun SignInLongHostGermanPreview() = RatatoskrTheme {
    Surface {
        SignInScreen(SignInUiState.Idle, serverHost = PREVIEW_HOST_WITH_PORT) { _, _ -> }
    }
}
