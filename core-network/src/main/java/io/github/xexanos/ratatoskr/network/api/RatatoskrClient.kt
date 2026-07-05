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
) {

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
        execute(sessionEndpoint = true) { playbackApi.getCurrentSession() }.map { it.toDomain() }

    /**
     * Starts playback. The stored refresh token is handed to the server so its sync loop can
     * renew the access token during long unattended playback (SPEC section 5 / contract
     * StartSessionRequest.refreshToken).
     */
    suspend fun startSession(itemId: String, speakerId: String): ApiResult<Session> {
        val refreshToken = tokenStore.refreshToken()
        return execute {
            playbackApi.startSession(StartSessionRequest(itemId, speakerId, refreshToken))
        }.map { it.toDomain() }
    }

    suspend fun stopSession(): ApiResult<Unit> =
        executeUnit(sessionEndpoint = true) { playbackApi.stopSession() }

    suspend fun pause(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.pauseSession() }.map { it.toDomain() }

    suspend fun resume(): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.resumeSession() }.map { it.toDomain() }

    suspend fun seek(positionSeconds: Double): ApiResult<Session> =
        execute(sessionEndpoint = true) { playbackApi.seekSession(SeekRequest(positionSeconds)) }
            .map { it.toDomain() }

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
        ApiResult.Failure(mapThrowable(t))
    }

    private suspend fun executeUnit(
        sessionEndpoint: Boolean = false,
        call: suspend () -> Response<Unit>,
    ): ApiResult<Unit> = try {
        val response = call()
        if (response.isSuccessful) ApiResult.Success(Unit)
        else ApiResult.Failure(mapHttpError(response.code(), response.errorBody()?.string(), sessionEndpoint))
    } catch (t: Throwable) {
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
