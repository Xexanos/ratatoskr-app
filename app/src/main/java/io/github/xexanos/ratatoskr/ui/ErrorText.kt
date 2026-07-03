/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui

import io.github.xexanos.ratatoskr.network.domain.RatatoskrError

/** Human-readable message for a domain error. UI-only; keeps error text out of core-network. */
fun RatatoskrError.toMessage(): String = when (this) {
    is RatatoskrError.Unauthorized -> "Sign-in expired. Please sign in again."
    is RatatoskrError.NoActiveSession -> "Nothing is playing right now."
    is RatatoskrError.NotFound -> "Not found."
    is RatatoskrError.Server -> message ?: "The server reported an error ($httpStatus)."
    is RatatoskrError.Upstream -> message ?: "Audiobookshelf or Sonos is unavailable."
    is RatatoskrError.CertificateUntrusted ->
        "The server certificate is not trusted. Re-confirm it in settings if it changed."
    is RatatoskrError.Network -> "Cannot reach the server. Check the URL and your network."
    is RatatoskrError.Unexpected -> "Something went wrong."
}
