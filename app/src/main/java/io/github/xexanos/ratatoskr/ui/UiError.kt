/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.xexanos.ratatoskr.R
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError

/**
 * A displayable error carried in UI state. ViewModels have no `Context`, so they store the error
 * as a value here and the Composable resolves it to a localized string via [text] - keeping error
 * copy out of the ViewModels and in `strings.xml` (translated per locale).
 */
sealed interface UiError {
    /** No trusted server is configured yet (the connection manager has no client). */
    data object NoServer : UiError

    /** A domain error surfaced by the network layer. */
    data class Domain(val error: RatatoskrError) : UiError
}

@Composable
fun UiError.text(): String = when (this) {
    UiError.NoServer -> stringResource(R.string.error_no_server)
    is UiError.Domain -> error.text()
}

/**
 * Localized message for a domain error. Server-provided messages ([RatatoskrError.Server],
 * [RatatoskrError.Upstream]) are passed through as-is (they come from upstream, already phrased);
 * everything else resolves to a translated string.
 */
@Composable
fun RatatoskrError.text(): String = when (this) {
    is RatatoskrError.Unauthorized -> stringResource(R.string.error_unauthorized)
    is RatatoskrError.NoActiveSession -> stringResource(R.string.error_no_active_session)
    is RatatoskrError.NotFound -> stringResource(R.string.error_not_found)
    is RatatoskrError.Server -> message ?: stringResource(R.string.error_server, httpStatus)
    is RatatoskrError.Upstream -> message ?: stringResource(R.string.error_upstream)
    is RatatoskrError.CertificateUntrusted -> stringResource(R.string.error_cert_untrusted)
    is RatatoskrError.Network -> stringResource(R.string.error_network)
    is RatatoskrError.Unexpected -> stringResource(R.string.error_unexpected)
}
