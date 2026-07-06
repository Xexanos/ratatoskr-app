/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.domain

import java.time.OffsetDateTime

/**
 * Domain models exposed by core-network. The UI layer only ever sees these; the generated
 * contract types stay behind the wrapper (SPEC section 13), so a contract change is absorbed
 * in the mappers in one place.
 */

/** How to reach one user's Ratatoskr server. */
data class ServerConfig(
    val baseUrl: String,
)

/** Details of a server TLS certificate, shown to the user during trust-on-first-use. */
data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notBefore: OffsetDateTime,
    val notAfter: OffsetDateTime,
    /** Lowercase, colon-separated SHA-256 of the DER encoding, e.g. "ab:cd:...". */
    val sha256Fingerprint: String,
)

data class AuthUser(
    val id: String,
    val username: String,
)

/** The token pair plus the authenticated user, as returned by login/refresh. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val user: AuthUser,
)

data class Speaker(
    val id: String,
    val name: String,
    val isGroup: Boolean,
    val members: List<String>,
)

data class Progress(
    val positionSeconds: Double,
    val isFinished: Boolean,
)

data class LibraryItemSummary(
    val id: String,
    val title: String,
    val author: String?,
    val durationSeconds: Double,
    val coverUrl: String?,
    val progress: Progress?,
)

data class LibraryItem(
    val summary: LibraryItemSummary,
    val description: String?,
    val narrator: String?,
)

data class LibraryPage(
    val items: List<LibraryItemSummary>,
    val nextCursor: String?,
)

enum class PlaybackState { PLAYING, PAUSED, BUFFERING, STOPPED, FINISHED, UNKNOWN }

data class Session(
    val itemId: String,
    val item: LibraryItemSummary?,
    val speakerId: String,
    val state: PlaybackState,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val updatedAt: OffsetDateTime,
)
