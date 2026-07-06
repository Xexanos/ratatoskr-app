/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Exchanges a refresh token for a new pair via the server. Returns null if refresh failed. */
fun interface TokenRefresher {
    fun refresh(refreshToken: String): AuthSession?
}

/** Attaches the bearer access token to every request except the unauthenticated auth endpoints. */
class BearerAuthInterceptor(
    private val tokenStore: TokenAccess,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.contains("/auth/")) {
            return chain.proceed(request)
        }
        val token = tokenStore.currentAccessTokenBlocking()
            ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
    }
}

/**
 * On a 401, exchanges the refresh token for a new pair and retries once (SPEC section 5).
 * A single-flight lock prevents concurrent refreshes from racing: whoever loses the lock
 * picks up the token the winner already stored instead of refreshing again.
 *
 * [refreshSuppressed] lets the app switch this off while a playback session is active - the
 * server then owns the refresh-token rotation for the session, and the app must not consume
 * the same refresh token independently (SPEC section 5). While suppressed a 401 is surfaced
 * so the app can re-fetch the session (which carries any rotated token) instead.
 */
class TokenRefreshAuthenticator(
    private val tokenStore: TokenAccess,
    private val refresher: TokenRefresher,
    private val refreshSuppressed: () -> Boolean = { false },
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (refreshSuppressed()) return null
        if (responseCount(response) >= 2) return null // already retried once; give up

        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(lock) {
            val current = tokenStore.currentAccessTokenBlocking()
            // Another thread refreshed while we waited for the lock: reuse its token.
            if (current != null && current != failedToken) {
                return response.request.retryWith(current)
            }
            val refreshToken = runBlocking { tokenStore.refreshToken() } ?: return null
            val renewed = refresher.refresh(refreshToken) ?: return null
            runBlocking { tokenStore.updateTokens(renewed.accessToken, renewed.refreshToken) }
            return response.request.retryWith(renewed.accessToken)
        }
    }

    private fun Request.retryWith(accessToken: String): Request =
        newBuilder().header("Authorization", "Bearer $accessToken").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
