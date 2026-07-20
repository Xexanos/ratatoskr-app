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
import io.github.xexanos.ratatoskr.network.generated.model.LibraryItemList as GenLibraryItemList
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

internal fun GenLibraryItemSummary.toDomain(cover: CoverEndpoint): LibraryItemSummary =
    LibraryItemSummary(
        id = id,
        title = title,
        author = author,
        durationSeconds = durationSeconds,
        coverUrl = cover.resolve(coverUrl),
        progress = progress?.toDomain(),
    )

internal fun GenLibraryItem.toDomain(cover: CoverEndpoint): LibraryItem =
    LibraryItem(
        summary = LibraryItemSummary(
            id = id,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            coverUrl = cover.resolve(coverUrl),
            progress = progress?.toDomain(),
        ),
        description = description,
        narrator = narrator,
    )

internal fun GenLibraryItemPage.toDomain(cover: CoverEndpoint): LibraryPage =
    LibraryPage(items = items.map { it.toDomain(cover) }, nextCursor = nextCursor)

/**
 * The in-progress shelf is a complete, bounded set, not a page - the envelope carries no
 * cursor, so it unwraps to a plain list of the existing summary model (no new domain type).
 */
internal fun GenLibraryItemList.toDomain(cover: CoverEndpoint): List<LibraryItemSummary> =
    items.map { it.toDomain(cover) }

internal fun GenPlaybackState.toDomain(): PlaybackState = when (this) {
    GenPlaybackState.playing -> PlaybackState.PLAYING
    GenPlaybackState.paused -> PlaybackState.PAUSED
    GenPlaybackState.buffering -> PlaybackState.BUFFERING
    GenPlaybackState.stopped -> PlaybackState.STOPPED
    GenPlaybackState.finished -> PlaybackState.FINISHED
}

internal fun GenSession.toDomain(cover: CoverEndpoint): Session =
    Session(
        itemId = itemId,
        item = item?.toDomain(cover),
        speakerId = speakerId,
        state = state.toDomain(),
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        updatedAt = updatedAt,
    )
