/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.domain

/** Outcome of a wrapped API call. The UI switches on this instead of catching exceptions. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Failure(val error: RatatoskrError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

/** Everything that can go wrong talking to the server, mapped away from HTTP/transport detail. */
sealed interface RatatoskrError {
    /**
     * The stored Ratatoskr token no longer works (HTTP 401). There is no refresh to fall back on
     * (SPEC section 5), so this always ends in the same recovery: sign in again. [code] is the
     * server's machine-readable reason, kept only to vary the sign-in notice's copy
     * (`UPSTREAM_SESSION_LOST` vs anything else), never the behaviour.
     */
    data class Unauthorized(val code: String? = null) : RatatoskrError

    /** Nothing is playing (HTTP 404 on a session endpoint). */
    data object NoActiveSession : RatatoskrError

    /** A requested resource was not found (HTTP 404 elsewhere). */
    data object NotFound : RatatoskrError

    /**
     * A route answered 404 without the contract's error body. Every 404 a real server sends
     * carries one, so a bare 404 means the server predates `/v2` entirely and is too old for
     * this app (SPEC section 5, rollout guard). The UI prompts to update the server.
     */
    data object ServerTooOld : RatatoskrError

    /** The server reported a structured error. [code] is its stable machine-readable code. */
    data class Server(val httpStatus: Int, val code: String?, val message: String?) : RatatoskrError

    /** A dependency of the server (Audiobookshelf or Sonos) failed (HTTP 502). */
    data class Upstream(val code: String?, val message: String?) : RatatoskrError

    /**
     * The TLS certificate is not trusted: either no trust has been established yet, or the
     * pinned fingerprint no longer matches (SPEC section 6). The UI routes this to the
     * connect/re-trust flow.
     */
    data class CertificateUntrusted(val message: String?) : RatatoskrError

    /** Transport-level failure (no connection, timeout, DNS, ...). */
    data class Network(val cause: Throwable) : RatatoskrError

    /** Anything not otherwise classified. */
    data class Unexpected(val cause: Throwable?) : RatatoskrError
}
