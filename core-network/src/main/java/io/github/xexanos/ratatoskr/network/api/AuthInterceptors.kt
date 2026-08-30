/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the Ratatoskr bearer token to every request except sign-in (SPEC section 5). Login
 * is the only exempt auth endpoint: logout REQUIRES the bearer - the server resolves the
 * presented token to the device session it revokes (contract 2.0.0). The token is opaque and
 * non-expiring: it is sent as-is and never refreshed or rotated on the app side, so there is no
 * authenticator here - a 401 is a terminal signal the wrapper surfaces, not something the
 * client can silently recover from.
 */
class BearerAuthInterceptor(
    private val tokenStore: TokenAccess,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.endsWith("/auth/login")) {
            return chain.proceed(request)
        }
        val token = tokenStore.currentTokenBlocking()
            ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build(),
        )
    }
}
