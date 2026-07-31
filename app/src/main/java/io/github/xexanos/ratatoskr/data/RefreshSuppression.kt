/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The /v1 refresh-suppression feed (SPEC section 5), isolated in one type so the /v2 cutover
 * deletes it wholesale. While a playback session is active the server owns refresh-token
 * rotation, so the OkHttp authenticator must not run the app's own `/auth/refresh` and race the
 * server for the same rotating token. [SessionManager] raises and lowers the flag as the session
 * comes and goes; [RatatoskrClientFactory][io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory]
 * reads [suppressed] into the authenticator.
 *
 * On /v2 there is no rotation and no suppression: this whole feed - the flag, its accessors, the
 * factory's `sessionActive` parameter, and [SessionManager]'s calls into it - is removed.
 */
class RefreshSuppression {

    private val active = AtomicBoolean(false)

    fun setSessionActive(active: Boolean) = this.active.set(active)

    fun isSessionActive(): Boolean = active.get()

    /** The read the client factory hands to the authenticator (a plain background-thread poll). */
    val suppressed: () -> Boolean = ::isSessionActive
}
