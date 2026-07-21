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
    Surface { ConnectScreen(ConnectUiState.Confirm("https://ratatoskr.home:8080", previewCert), {}, { _, _ -> }, {}) }
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
