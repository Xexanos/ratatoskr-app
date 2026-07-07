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
    /** Missing/invalid token (HTTP 401), after refresh has been attempted and failed. */
    data object Unauthorized : RatatoskrError

    /** Nothing is playing (HTTP 404 on a session endpoint). */
    data object NoActiveSession : RatatoskrError

    /** A requested resource was not found (HTTP 404 elsewhere). */
    data object NotFound : RatatoskrError

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
