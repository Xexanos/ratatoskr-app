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
import io.github.xexanos.ratatoskr.network.generated.model.PlaybackState
import io.github.xexanos.ratatoskr.network.generated.model.RefreshRequest
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import io.github.xexanos.ratatoskr.network.tls.PinnedHostnameVerifier
import io.github.xexanos.ratatoskr.network.tls.PinnedTrustManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.EnumJsonAdapter
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

/**
 * The app's Moshi: the generated [Serializer.moshi] plus an unknown-value fallback for
 * [PlaybackState]. The kotlin generator's enum adapter throws on an unrecognised value, which
 * would fail the whole `Session` response; instead a future/unknown state deserializes to a
 * benign [PlaybackState.stopped] so an older app keeps working against a newer server
 * (SPEC section 4). STOPPED is the neutral choice - it never implies audio is playing.
 */
internal fun ratatoskrMoshi(): Moshi =
    Serializer.moshi.newBuilder()
        .add(
            PlaybackState::class.java,
            EnumJsonAdapter.create(PlaybackState::class.java).withUnknownFallback(PlaybackState.stopped),
        )
        .build()

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
        // keep Authorization redacted - never log tokens, headers, or bodies (SPEC section 11).
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = HttpLoggingInterceptor.Level.NONE
        }

        val baseBuilder = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            // Pin-aware hostname verification (SPEC section 6): when the user-confirmed
            // fingerprint matches the served leaf, the hostname is ignored - the confirmed
            // certificate is trustworthy at any address (a LAN IP, the E2E emulator's
            // 10.0.2.2). On the platform-trusted path (reverse proxy, public CA) the standard
            // verifier still applies; there it is the only host binding, because
            // PinnedTrustManager consults the pin only after platform validation fails.
            .hostnameVerifier(PinnedHostnameVerifier(fingerprint))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)

        val retrofitBase = "${baseUrl.trimEnd('/')}/v1/"

        // Shared across both Retrofit instances so response bodies get the unknown-enum
        // fallback (an unrecognised PlaybackState degrades to STOPPED instead of failing the
        // whole response, SPEC section 4). The plain Serializer.moshi would throw.
        val moshi = ratatoskrMoshi()

        // A client without bearer/authenticator, used only for login and refresh so a refresh
        // never recurses through the authenticator. It gets its OWN dispatcher: a refresh is
        // issued from inside the main client's authenticator while the original request still
        // occupies a dispatcher slot, so sharing one dispatcher deadlocks once concurrent 401s
        // fill maxRequestsPerHost (5 by default) - the refresh can never get a slot, and the
        // requests waiting on it never free theirs. A separate dispatcher keeps refresh
        // admission independent of the requests it unblocks.
        val authClient = baseBuilder
            .dispatcher(Dispatcher())
            .build()
        val authSystemApi = retrofit(retrofitBase, authClient, moshi).create(SystemApi::class.java)

        val refresher = TokenRefresher { refreshToken ->
            runBlocking {
                val response = authSystemApi.refresh(RefreshRequest(refreshToken))
                if (response.isSuccessful) response.body()?.toDomain() else null
            }
        }

        val mainClient = baseBuilder
            .dispatcher(Dispatcher())
            .addInterceptor(BearerAuthInterceptor(tokenStore))
            .authenticator(TokenRefreshAuthenticator(tokenStore, refresher, sessionActive))
            .build()
        val mainRetrofit = retrofit(retrofitBase, mainClient, moshi)

        // Cover-image loads share the main client's connection pool (TLS handshakes against a
        // TOFU-pinned server are not cheap to redo) and its interceptors/authenticator - the
        // bearer header and the single-flight refresh come along for free - but get their OWN
        // dispatcher: covers and API calls target the same host, and OkHttp admits only 5
        // concurrent requests per host per dispatcher, so a scroll burst of cover loads sharing
        // the main dispatcher would queue play/pause/seek and the session poll behind images.
        val coversClient = mainClient.newBuilder()
            .dispatcher(Dispatcher())
            .build()

        // Each OkHttp stack owns its own dispatcher thread pool. All three clients share ONE
        // connection pool (auth/main build from the same baseBuilder, covers via newBuilder),
        // so two of the three evictAll calls are idempotent repeats - kept uniform per client
        // on purpose, so the cleanup stays correct if any client ever gets its own pool.
        // Release everything when the client is replaced so it does not linger until GC
        // (SPEC section 13).
        val closeAction = {
            for (client in listOf(mainClient, authClient, coversClient)) {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

        return RatatoskrClient(
            systemApi = mainRetrofit.create(SystemApi::class.java),
            speakersApi = mainRetrofit.create(SpeakersApi::class.java),
            libraryApi = mainRetrofit.create(LibraryApi::class.java),
            playbackApi = mainRetrofit.create(PlaybackApi::class.java),
            tokenStore = tokenStore,
            moshi = moshi,
            coverEndpoint = CoverEndpoint(baseUrl),
            coversCallFactory = coversClient,
            closeAction = closeAction,
        )
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
}
