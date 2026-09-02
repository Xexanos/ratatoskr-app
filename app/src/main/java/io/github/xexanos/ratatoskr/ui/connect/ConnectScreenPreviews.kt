/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.connect

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import io.github.xexanos.ratatoskr.network.domain.CertificateInfo
import io.github.xexanos.ratatoskr.ui.LocalImmediateLoading
import io.github.xexanos.ratatoskr.ui.theme.RatatoskrTheme
import java.time.OffsetDateTime

// Previews / screenshot goldens for the connect-and-trust screen (render in Android Studio
// without a running server), driving the public [ConnectScreen] off a fixed state (ADR 0001).

private const val PREVIEW_BASE_URL = "https://ratatoskr.home:8080"

private val previewCert = CertificateInfo(
    subject = "CN=ratatoskr.home",
    issuer = "CN=ratatoskr.home",
    notBefore = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    notAfter = OffsetDateTime.parse("2027-01-01T00:00:00Z"),
    sha256Fingerprint = "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90:" +
        "ab:cd:ef:12:34:56:78:90:ab:cd:ef:12:34:56:78:90",
)

@Preview(name = "Connect - idle", widthDp = 360, heightDp = 800)
@Composable
internal fun ConnectIdlePreview() = RatatoskrTheme {
    Surface { ConnectScreen(ConnectUiState.Idle, {}, { _, _ -> }, {}) }
}

@Preview(name = "Connect - confirm certificate", widthDp = 360, heightDp = 800)
@Composable
internal fun ConnectConfirmPreview() = RatatoskrTheme {
    Surface { ConnectScreen(ConnectUiState.Confirm(PREVIEW_BASE_URL, previewCert), {}, { _, _ -> }, {}) }
}

// The smallest height the design draws for, and the case the 800 dp previews cannot show: the
// whole certificate - fingerprint and the instruction to compare it - on screen at the same time
// as the copper action that acts on it. Both used to fall below the fold here, the actions
// because they rode inside the card and the compare hint because the welcome block was still
// above it.
@Preview(name = "Connect - confirm, short screen", widthDp = 360, heightDp = 600)
@Composable
internal fun ConnectConfirmShortScreenPreview() = RatatoskrTheme {
    Surface { ConnectScreen(ConnectUiState.Confirm(PREVIEW_BASE_URL, previewCert), {}, { _, _ -> }, {}) }
}

// The pinned slot's worst case for width and height at once (ux-design: "layouts survive +30%
// text"): the confirm step in the locale whose labels for both actions are the longer ones, on
// the short screen. Sign-in carries the same guard for its chip.
@Preview(name = "Connect - confirm, de", widthDp = 360, heightDp = 600, locale = "de")
@Composable
internal fun ConnectConfirmGermanPreview() = RatatoskrTheme {
    Surface { ConnectScreen(ConnectUiState.Confirm(PREVIEW_BASE_URL, previewCert), {}, { _, _ -> }, {}) }
}

@Preview(name = "Connect - error", widthDp = 360, heightDp = 800)
@Composable
internal fun ConnectErrorPreview() = RatatoskrTheme {
    Surface { ConnectScreen(ConnectUiState.Error("Could not read the server certificate."), {}, { _, _ -> }, {}) }
}

// Opens the 500 ms loading gate (see [LocalImmediateLoading]) so the loader is in the frame.
@Preview(name = "Connect - inspecting", widthDp = 360, heightDp = 800)
@Composable
internal fun ConnectInspectingPreview() = RatatoskrTheme {
    CompositionLocalProvider(LocalImmediateLoading provides true) {
        Surface { ConnectScreen(ConnectUiState.Inspecting, {}, { _, _ -> }, {}) }
    }
}
