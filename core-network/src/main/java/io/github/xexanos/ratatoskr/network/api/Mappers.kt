/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import io.github.xexanos.ratatoskr.network.domain.LibraryItem
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.LibraryPage
import io.github.xexanos.ratatoskr.network.domain.PlaybackState
import io.github.xexanos.ratatoskr.network.domain.Progress
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.network.generated.model.AuthTokens as GenAuthTokens
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItem as GenLibraryItem
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItemPage as GenLibraryItemPage
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItemSummary as GenLibraryItemSummary
import io.github.xexanos.ratatoskr.network.generated.model.PlaybackState as GenPlaybackState
import io.github.xexanos.ratatoskr.network.generated.model.Progress as GenProgress
import io.github.xexanos.ratatoskr.network.generated.model.Session as GenSession
import io.github.xexanos.ratatoskr.network.generated.model.Speaker as GenSpeaker

/**
 * The single place where generated contract types become domain models (SPEC section 13).
 * A contract field change is absorbed here, so the UI never depends on the generated code.
 */
internal fun GenAuthTokens.toDomain(): AuthSession =
    AuthSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        user = AuthUser(id = user.id, username = user.username),
    )

internal fun GenSpeaker.toDomain(): Speaker =
    Speaker(
        id = id,
        name = name,
        isGroup = isGroup,
        members = members.orEmpty(),
    )

internal fun GenProgress.toDomain(): Progress =
    Progress(positionSeconds = positionSeconds, isFinished = isFinished)

/**
 * Resolves the contract's `coverUrl` into a loadable absolute URL. Since contract 1.3.x the
 * server sends a path relative to its own origin (e.g. `/v1/library/items/{id}/cover`); the
 * wrapper absorbs that here so the domain model always carries an absolute, bearer-authenticated
 * URL and no UI code needs to know the server origin. An already-absolute value passes through
 * untouched (tolerant reader: contract 1.1.0 documented an absolute URL, a future server could
 * again send one).
 */
internal fun resolveCoverUrl(coverUrl: String?, baseUrl: String): String? = when {
    coverUrl == null -> null
    coverUrl.startsWith("http://") || coverUrl.startsWith("https://") -> coverUrl
    else -> baseUrl.trimEnd('/') + (if (coverUrl.startsWith("/")) coverUrl else "/$coverUrl")
}

internal fun GenLibraryItemSummary.toDomain(baseUrl: String): LibraryItemSummary =
    LibraryItemSummary(
        id = id,
        title = title,
        author = author,
        durationSeconds = durationSeconds,
        coverUrl = resolveCoverUrl(coverUrl, baseUrl),
        progress = progress?.toDomain(),
    )

internal fun GenLibraryItem.toDomain(baseUrl: String): LibraryItem =
    LibraryItem(
        summary = LibraryItemSummary(
            id = id,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            coverUrl = resolveCoverUrl(coverUrl, baseUrl),
            progress = progress?.toDomain(),
        ),
        description = description,
        narrator = narrator,
    )

internal fun GenLibraryItemPage.toDomain(baseUrl: String): LibraryPage =
    LibraryPage(items = items.map { it.toDomain(baseUrl) }, nextCursor = nextCursor)

internal fun GenPlaybackState.toDomain(): PlaybackState = when (this) {
    GenPlaybackState.playing -> PlaybackState.PLAYING
    GenPlaybackState.paused -> PlaybackState.PAUSED
    GenPlaybackState.buffering -> PlaybackState.BUFFERING
    GenPlaybackState.stopped -> PlaybackState.STOPPED
    GenPlaybackState.finished -> PlaybackState.FINISHED
}

internal fun GenSession.toDomain(baseUrl: String): Session =
    Session(
        itemId = itemId,
        item = item?.toDomain(baseUrl),
        speakerId = speakerId,
        state = state.toDomain(),
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        updatedAt = updatedAt,
    )
