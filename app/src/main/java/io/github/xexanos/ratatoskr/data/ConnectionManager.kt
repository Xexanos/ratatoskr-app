/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.persist.ConnectionStore
import io.github.xexanos.ratatoskr.network.persist.CredentialStore
import io.github.xexanos.ratatoskr.network.persist.TokenAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Why the next visit to the sign-in screen deserves an explanatory notice (SPEC section 5). Its
 * presence means the user did not choose to sign in - a dead token or the /v1 -> /v2 update sent
 * them there. It varies only the notice's copy, never the sign-in behaviour; absent on an
 * ordinary visit.
 */
sealed interface SignInPrompt {
    /**
     * Returned to sign-in by a 401; [code] is the body's machine-readable reason, kept only to
     * vary the notice's copy and null when the 401 carried none.
     */
    data class Reauth(val code: String?) : SignInPrompt

    /** First launch after the /v1 -> /v2 update: the one-time re-login (SPEC section 5). */
    data object AppUpdated : SignInPrompt
}

/**
 * Owns the current [RatatoskrClient], rebuilding it whenever the trusted server or its
 * fingerprint changes. Returns null until the user has confirmed a server certificate
 * (SPEC section 6), which is how the UI knows to route to the connect screen.
 */
class ConnectionManager(
    val connectionStore: ConnectionStore,
    // The token-shaped network handle: passed to the factory (which attaches the bearer) and
    // used by the tests. App code goes through [credentials], never the token itself.
    val tokenStore: TokenAccess,
) {
    /**
     * The credential-shaped seam app code uses for auth state (SPEC section 5): a presence check
     * and a clear, nothing token-shaped. Backed by [tokenStore].
     */
    val credentials: CredentialStore get() = tokenStore

    // Guards client construction so concurrent callers (e.g. the poll loop and a screen right
    // after sign-in) cannot each build a client: two OkHttp stacks would each hold their own
    // dispatcher and connection pool for the same server, wasting sockets and threads until GC.
    // @Volatile makes the fast-path read see the winner's write.
    private val buildMutex = Mutex()

    // The client and the key it was built for, held as one object so the lock-free fast path
    // reads them in a single volatile load and can never observe a torn (client, key) pair.
    private data class Cached(val key: String, val client: RatatoskrClient)

    @Volatile private var cached: Cached? = null

    // Set when a call surfaces an Unauthorized the app cannot recover from: the stored Ratatoskr
    // token no longer works against this server (revoked, or the server's upstream session died),
    // and there is no refresh to fall back on (SPEC section 5). The nav host observes this and
    // routes to the sign-in screen so the user re-enters credentials, instead of being stranded
    // on a dead-end error whose retry never recovers. The trusted server, its certificate, and the
    // display username are kept; only the token is cleared, so sign-in comes up pre-filled.
    private val _reauthRequired = MutableStateFlow(false)
    val reauthRequired: StateFlow<Boolean> = _reauthRequired.asStateFlow()

    // The prompt that is pending, read once by the sign-in screen to choose its notice copy (SPEC
    // section 5): a 401 reauth (whose code varies UPSTREAM_SESSION_LOST vs anything else) or the
    // one-time /v1 -> /v2 re-login. Consumed on read so a later, ordinary visit to sign-in shows
    // no stale notice. Null means the visit needs no notice.
    @Volatile private var pendingPrompt: SignInPrompt? = null

    /**
     * Terminal auth failure: discard the stranded token (keeping the username for the sign-in
     * pre-fill) and signal the UI to send the user back to sign-in. [code] is the 401 body's code,
     * kept only to vary the sign-in notice. Idempotent - safe to call from several failing calls at
     * once. Cleared by [acknowledgeReauth] once the nav host has routed.
     */
    suspend fun requireReauth(code: String?) {
        tokenStore.clearToken()
        // Set the prompt before raising the flag: the nav host routes on the flag and the sign-in
        // screen then reads the prompt, so it must already be in place.
        pendingPrompt = SignInPrompt.Reauth(code)
        _reauthRequired.value = true
    }

    /** The nav host calls this after routing to sign-in, so the signal does not re-fire. */
    fun acknowledgeReauth() {
        _reauthRequired.value = false
    }

    /**
     * The one-time /v1 -> /v2 migration (SPEC section 5), run on every launch before the routing
     * credential check: discards the Audiobookshelf token pair a pre-update install left behind
     * and queues the "app updated" sign-in notice. The trusted server, its certificate, and the
     * username are untouched, so that launch routes to a pre-filled sign-in on its own - no
     * navigation signal needed. Every launch but the first after the update finds nothing and
     * changes nothing.
     */
    suspend fun migrateFromV1() {
        if (tokenStore.discardLegacyTokens()) {
            pendingPrompt = SignInPrompt.AppUpdated
        }
    }

    /**
     * The pending sign-in prompt, consumed on read (returns it once, then null). The sign-in
     * screen calls this to decide whether - and which - explanatory notice to show. Null when the
     * user reached sign-in by an ordinary route.
     */
    fun consumeSignInPrompt(): SignInPrompt? = pendingPrompt.also { pendingPrompt = null }

    /** The remembered display username for the sign-in pre-fill, or null if none is stored. */
    suspend fun prefillUsername(): String? = tokenStore.username()

    /**
     * The trusted server's base URL, or null before one is configured. The one read the screens
     * that name the server share, so neither walks the store itself.
     */
    suspend fun serverBaseUrl(): String? = connectionStore.currentServerConfig()?.baseUrl

    /**
     * The already-built client, without building one: a lock-free volatile read. Cover-image
     * loads resolve their Call.Factory through this per request - by the time any cover URL
     * exists on screen, the library data that carried it was fetched through [client], so the
     * cache is populated; before that, a null here simply fails the image request into its
     * placeholder state.
     */
    fun peekClient(): RatatoskrClient? = cached?.client

    /** The client for the currently trusted server, or null if none is configured yet. */
    suspend fun client(): RatatoskrClient? {
        val config = connectionStore.currentServerConfig() ?: return null
        val fingerprint = connectionStore.fingerprint() ?: return null
        val key = "${config.baseUrl}|$fingerprint"
        cached?.let { if (it.key == key) return it.client }
        return buildMutex.withLock {
            // Re-check inside the lock: another caller may have built it while we waited.
            cached?.let { if (it.key == key) return@withLock it.client }
            // A cached client for a different key is being replaced - release its HTTP stack.
            cached?.client?.let { closeClient(it) }
            RatatoskrClientFactory.create(
                baseUrl = config.baseUrl,
                fingerprint = fingerprint,
                tokenStore = tokenStore,
            ).also {
                cached = Cached(key, it)
            }
        }
    }

    /**
     * Drop the cached client after the server or certificate changed, releasing its HTTP stack.
     * Takes [buildMutex] so it cannot race a concurrent [client] build: without it, a build that
     * started before this call could still overwrite [cached] with a client for the
     * now-invalidated key after this call has cleared it.
     */
    suspend fun invalidate() {
        buildMutex.withLock {
            val toClose = cached?.client
            cached = null
            toClose?.let { closeClient(it) }
        }
    }

    // RatatoskrClient.close() evicts the OkHttp connection pool, which flushes and closes live
    // TLS sockets - blocking network I/O. Callers invalidate from viewModelScope
    // (Dispatchers.Main), so closing on the caller's thread throws NetworkOnMainThreadException
    // (and would crash forget-certificate, where a live client exists). Tear down off the main
    // thread. NonCancellable: this releases resources for a client that is already unreachable
    // from `cached`, so it must run to completion even if the caller's coroutine (e.g. a
    // ViewModel scope cleared by an activity recreation) is cancelled mid-close.
    private suspend fun closeClient(client: RatatoskrClient) {
        withContext(NonCancellable + Dispatchers.IO) { client.close() }
    }
}
