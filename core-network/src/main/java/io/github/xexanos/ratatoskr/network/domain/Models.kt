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

/** The opaque Ratatoskr token plus the authenticated user, as returned by /v2 login (SPEC section 5). */
data class AuthSession(
    val token: String,
    val user: AuthUser,
)

/**
 * The app's single, opaque auth credential (SPEC section 5). The app module treats it as a
 * black box: it only checks presence (signed in or not) and clears it on sign-out, and the
 * [value] never leaves core-network - its constructor and field are `internal`, so no
 * cross-module caller can read or forge it.
 *
 * It carries the one stored Ratatoskr token the client sends as its bearer: a non-expiring,
 * server-issued credential with no lifecycle on the app side (SPEC section 5).
 */
@JvmInline
value class Credential internal constructor(internal val value: String)

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
