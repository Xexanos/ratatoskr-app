/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.api

import io.github.xexanos.ratatoskr.network.generated.api.LibraryApi
import io.github.xexanos.ratatoskr.network.generated.api.PlaybackApi
import io.github.xexanos.ratatoskr.network.generated.api.SpeakersApi
import io.github.xexanos.ratatoskr.network.generated.api.SystemApi
import io.github.xexanos.ratatoskr.network.generated.infrastructure.Serializer
import io.github.xexanos.ratatoskr.network.generated.model.RefreshRequest
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import io.github.xexanos.ratatoskr.network.tls.PinnedTrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Assembles a [RatatoskrClient] for one server: an OkHttp stack with the trust-on-first-use
 * trust manager (SPEC section 6) and the bearer/refresh auth (SPEC section 5), plus a
 * Retrofit bound to the generated APIs.
 *
 * Rebuild the client whenever the server URL or the confirmed fingerprint changes.
 */
object RatatoskrClientFactory {

    fun create(
        baseUrl: String,
        fingerprint: String?,
        tokenStore: TokenAccess,
        sessionActive: () -> Boolean = { false },
    ): RatatoskrClient {
        val trustManager = PinnedTrustManager(fingerprint)
        val sslSocketFactory = PinnedTrustManager.socketFactory(trustManager)

        // Logging is off by default. If raised for debugging it must stay at BASIC or below and
        // keep Authorization redacted -- never log tokens, headers, or bodies (SPEC section 11).
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = HttpLoggingInterceptor.Level.NONE
        }

        val baseBuilder = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)

        val retrofitBase = "${baseUrl.trimEnd('/')}/v1/"

        // A client without bearer/authenticator, used only for login and refresh so a refresh
        // never recurses through the authenticator.
        val authClient = baseBuilder.build()
        val authSystemApi = retrofit(retrofitBase, authClient).create(SystemApi::class.java)

        val refresher = TokenRefresher { refreshToken ->
            runBlocking {
                val response = authSystemApi.refresh(RefreshRequest(refreshToken))
                if (response.isSuccessful) response.body()?.toDomain() else null
            }
        }

        val mainClient = baseBuilder
            .addInterceptor(BearerAuthInterceptor(tokenStore))
            .authenticator(TokenRefreshAuthenticator(tokenStore, refresher, sessionActive))
            .build()
        val mainRetrofit = retrofit(retrofitBase, mainClient)

        return RatatoskrClient(
            systemApi = mainRetrofit.create(SystemApi::class.java),
            speakersApi = mainRetrofit.create(SpeakersApi::class.java),
            libraryApi = mainRetrofit.create(LibraryApi::class.java),
            playbackApi = mainRetrofit.create(PlaybackApi::class.java),
            tokenStore = tokenStore,
            moshi = Serializer.moshi,
        )
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
            .build()
}
