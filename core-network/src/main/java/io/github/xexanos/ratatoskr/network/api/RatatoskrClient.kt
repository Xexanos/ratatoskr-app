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
import io.github.xexanos.ratatoskr.network.generated.model.Session as GenSession
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
        execute { libraryApi.listLibraryItems(query, limit, cursor) }.map { it.toDomain() }

    suspend fun getLibraryItem(itemId: String): ApiResult<LibraryItem> =
        execute { libraryApi.getLibraryItem(itemId) }.map { it.toDomain() }

    suspend fun currentSession(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.getCurrentSession() }
            .adoptingRotatedTokens().map { it.toDomain() }

    /**
     * Starts playback. The stored refresh token is handed to the server so its sync loop can
     * renew the access token during long unattended playback (SPEC section 5 / contract
     * StartSessionRequest.refreshToken).
     */
    suspend fun startSession(itemId: String, speakerId: String): ApiResult<Session> {
        val refreshToken = tokenStore.refreshToken()
        return execute {
            playbackApi.startSession(StartSessionRequest(itemId, speakerId, refreshToken))
        }.adoptingRotatedTokens().map { it.toDomain() }
    }

    /**
     * Stops playback. The contract returns 204 normally, or 200 with a final [GenSession]
     * carrying a still-pending rotated token pair (SPEC section 5); either counts as success.
     * Any pair in a 200 body is adopted before the session ends, since the server discards its
     * in-memory tokens on stop and cannot redeliver them.
     */
    suspend fun stopSession(): ApiResult<Unit> =
        when (val result = executeNullable(sessionEndpoint = true) { playbackApi.stopSession() }) {
            is ApiResult.Success -> {
                result.data?.adoptRotatedTokens()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> result
        }

    suspend fun pause(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.pauseSession() }
            .adoptingRotatedTokens().map { it.toDomain() }

    suspend fun resume(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.resumeSession() }
            .adoptingRotatedTokens().map { it.toDomain() }

    suspend fun seek(positionSeconds: Double): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.seekSession(SeekRequest(positionSeconds)) }
            .adoptingRotatedTokens().map { it.toDomain() }

    // --- token adoption -----------------------------------------------------------------

    /**
     * Adopts a rotated token pair carried on a successful [GenSession] response and passes the
     * result through unchanged. This is how the app learns the tokens the server rotated during
     * an active session, since it does not refresh on its own while a session is live
     * (SPEC section 5).
     */
    private suspend fun ApiResult<GenSession>.adoptingRotatedTokens(): ApiResult<GenSession> {
        (this as? ApiResult.Success)?.data?.adoptRotatedTokens()
        return this
    }

    private suspend fun GenSession.adoptRotatedTokens() {
        rotatedTokens?.let { tokenStore.updateTokens(it.accessToken, it.refreshToken) }
    }

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
            401 -> RatatoskrError.Unauthorized
            404 -> if (sessionEndpoint) RatatoskrError.NoActiveSession else RatatoskrError.NotFound
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
