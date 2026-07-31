/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import io.github.xexanos.ratatoskr.network.generated.model.Session as GenSession

/**
 * The /v1 refresh-token rotation-adoption protocol (SPEC section 5), isolated in one type so the
 * /v2 cutover deletes it wholesale.
 *
 * While a playback session is active the app does not run its own refresh (the server owns the
 * rotation); instead it *adopts* the tokens the server rotated, which ride back on the session
 * responses. And at start it hands the server the current refresh token so the server's sync
 * loop can rotate during long unattended playback (contract `StartSessionRequest.refreshToken`).
 *
 * On /v2 there is no rotation: the credential is a single non-expiring token, this whole seam is
 * removed, and [RatatoskrClient]'s playback calls become plain request/response mappings.
 */
internal class SessionRotationAdoption(private val tokenStore: TokenAccess) {

    /** The refresh token handed to the server at `startSession`, or null if none is stored. */
    suspend fun refreshTokenForHandoff(): String? = tokenStore.refreshToken()

    /**
     * Adopts a rotated pair carried on a successful session response and passes the result
     * through unchanged, so callers can keep mapping the body: this is how the app learns the
     * tokens the server rotated during an active session.
     */
    suspend fun adopt(result: ApiResult<GenSession>): ApiResult<GenSession> {
        (result as? ApiResult.Success)?.data?.let { adopt(it) }
        return result
    }

    /**
     * Adopts a rotated pair from a (possibly absent) session body - e.g. `stopSession`'s
     * 200-with-Session form, whose pair must be taken before the session ends since the server
     * discards its in-memory tokens on stop and cannot redeliver them.
     */
    suspend fun adopt(session: GenSession?) {
        session?.rotatedTokens?.let { tokenStore.updateTokens(it.accessToken, it.refreshToken) }
    }
}
