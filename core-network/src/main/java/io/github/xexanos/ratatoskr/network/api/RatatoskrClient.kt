/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.map
import io.github.xexanos.ratatoskr.network.domain.AuthSession
import io.github.xexanos.ratatoskr.network.domain.LibraryItem
import io.github.xexanos.ratatoskr.network.domain.LibraryItemSummary
import io.github.xexanos.ratatoskr.network.domain.LibraryPage
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.domain.Session
import io.github.xexanos.ratatoskr.network.domain.Speaker
import io.github.xexanos.ratatoskr.network.generated.api.LibraryApi
import io.github.xexanos.ratatoskr.network.generated.api.PlaybackApi
import io.github.xexanos.ratatoskr.network.generated.api.SpeakersApi
import io.github.xexanos.ratatoskr.network.generated.api.SystemApi
import io.github.xexanos.ratatoskr.network.generated.model.LoginRequest
import io.github.xexanos.ratatoskr.network.generated.model.SeekRequest
import io.github.xexanos.ratatoskr.network.generated.model.StartSessionRequest
import io.github.xexanos.ratatoskr.network.generated.model.Error as GenError
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import com.squareup.moshi.Moshi
import retrofit2.Response
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The thin wrapper the app depends on instead of the generated client (SPEC section 4).
 * It returns domain models inside [ApiResult] and translates HTTP/transport failures into
 * [RatatoskrError], so the UI never touches Retrofit, HTTP codes, or generated types.
 *
 * Build instances with [RatatoskrClientFactory]; the factory wires TLS trust and auth.
 */
class RatatoskrClient internal constructor(
    private val systemApi: SystemApi,
    private val speakersApi: SpeakersApi,
    private val libraryApi: LibraryApi,
    private val playbackApi: PlaybackApi,
    private val tokenStore: TokenAccess,
    private val moshi: Moshi,
    private val coverEndpoint: CoverEndpoint,
    /**
     * OkHttp stack for loading cover images, sharing this client's TLS trust (TOFU pin,
     * SPEC section 6) and bearer auth (SPEC section 5) but with its own dispatcher, so a scroll
     * burst of cover requests can never queue playback commands or the session poll behind it
     * (OkHttp admission is per-dispatcher, default 5 per host - and covers share the API's host).
     * Consumed by the app's image loader; closed with [close].
     */
    val coversCallFactory: okhttp3.Call.Factory,
    private val closeAction: () -> Unit = {},
) {

    /**
     * Releases the underlying OkHttp resources (dispatcher threads and pooled sockets). Call
     * when this client is replaced - the owner does so on a server/certificate change - so the
     * old HTTP stack does not linger until GC (SPEC section 13).
     */
    fun close() = closeAction()

    suspend fun login(username: String, password: String): ApiResult<AuthSession> {
        val result = execute { systemApi.login(LoginRequest(username, password)) }
            .map { it.toDomain() }
        if (result is ApiResult.Success) {
            tokenStore.save(result.data)
        }
        return result
    }

    suspend fun signOut() {
        tokenStore.clear()
    }

    suspend fun listSpeakers(): ApiResult<List<Speaker>> =
        execute { speakersApi.listSpeakers() }.map { list -> list.map { it.toDomain() } }

    suspend fun listLibraryItems(
        query: String? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): ApiResult<LibraryPage> =
        execute { libraryApi.listLibraryItems(query, limit, cursor) }.map { it.toDomain(coverEndpoint) }

    /**
     * The continue-listening shelf: in-progress books, most-recently-listened first. Membership
     * and order are the server's; the shelf size is its default too - `limit` is deliberately
     * not sent (the generated default of 25 would silently pin the server's choice, so it is
     * overridden with null here).
     */
    suspend fun listInProgressItems(): ApiResult<List<LibraryItemSummary>> =
        execute { libraryApi.listInProgressItems(limit = null) }.map { it.toDomain(coverEndpoint) }

    suspend fun getLibraryItem(itemId: String): ApiResult<LibraryItem> =
        execute { libraryApi.getLibraryItem(itemId) }.map { it.toDomain(coverEndpoint) }

    suspend fun currentSession(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.getCurrentSession() }
            .map { it.toDomain(coverEndpoint) }

    /**
     * Starts playback. On /v2 the server owns the whole session lifecycle from its own stored
     * Audiobookshelf credential (ADR-0001), so the request carries only what to play and where.
     */
    suspend fun startSession(itemId: String, speakerId: String): ApiResult<Session> =
        execute { playbackApi.startSession(StartSessionRequest(itemId, speakerId)) }
            .map { it.toDomain(coverEndpoint) }

    /** Stops playback. The contract returns 204 on success, so an empty body still counts. */
    suspend fun stopSession(): ApiResult<Unit> =
        when (val result = executeNullable(sessionEndpoint = true) { playbackApi.stopSession() }) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    suspend fun pause(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.pauseSession() }
            .map { it.toDomain(coverEndpoint) }

    suspend fun resume(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.resumeSession() }
            .map { it.toDomain(coverEndpoint) }

    suspend fun seek(positionSeconds: Double): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.seekSession(SeekRequest(positionSeconds)) }
            .map { it.toDomain(coverEndpoint) }

    // --- error handling -----------------------------------------------------------------

    private suspend fun <T : Any> execute(
        sessionEndpoint: Boolean = false,
        call: suspend () -> Response<T>,
    ): ApiResult<T> = try {
        val response = call()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) ApiResult.Success(body)
            else ApiResult.Failure(RatatoskrError.Unexpected(IllegalStateException("Empty response body")))
        } else {
            ApiResult.Failure(mapHttpError(response.code(), response.errorBody()?.string(), sessionEndpoint))
        }
    } catch (t: Throwable) {
        // Never swallow coroutine cancellation: rethrow so structured concurrency can unwind
        // instead of the cancelled call writing a spurious error state.
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        ApiResult.Failure(mapThrowable(t))
    }

    /**
     * Like [execute] but tolerates an empty body: a 204 (or any success with no body) becomes
     * `Success(null)` rather than a failure. Used where the contract allows either a body or an
     * empty success, e.g. stopSession returning 204 or 200-with-Session.
     */
    private suspend fun <T : Any> executeNullable(
        sessionEndpoint: Boolean = false,
        call: suspend () -> Response<T>,
    ): ApiResult<T?> = try {
        val response = call()
        if (response.isSuccessful) ApiResult.Success(response.body())
        else ApiResult.Failure(mapHttpError(response.code(), response.errorBody()?.string(), sessionEndpoint))
    } catch (t: Throwable) {
        // Never swallow coroutine cancellation: rethrow so structured concurrency can unwind
        // instead of the cancelled call writing a spurious error state.
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        ApiResult.Failure(mapThrowable(t))
    }

    private fun mapHttpError(status: Int, errorBody: String?, sessionEndpoint: Boolean): RatatoskrError {
        val parsed = errorBody?.let { runCatching { moshi.adapter(GenError::class.java).fromJson(it) }.getOrNull() }
        return when (status) {
            401 -> RatatoskrError.Unauthorized(parsed?.code)
            404 -> when {
                parsed == null -> RatatoskrError.ServerTooOld
                sessionEndpoint -> RatatoskrError.NoActiveSession
                else -> RatatoskrError.NotFound
            }
            502 -> RatatoskrError.Upstream(parsed?.code, parsed?.message)
            else -> RatatoskrError.Server(status, parsed?.code, parsed?.message)
        }
    }

    private fun mapThrowable(t: Throwable): RatatoskrError {
        var cause: Throwable? = t
        while (cause != null) {
            when (cause) {
                is CertificateException,
                is SSLPeerUnverifiedException,
                is SSLHandshakeException,
                -> return RatatoskrError.CertificateUntrusted(cause.message)
            }
            cause = cause.cause
        }
        return if (t is IOException) RatatoskrError.Network(t) else RatatoskrError.Unexpected(t)
    }
}
