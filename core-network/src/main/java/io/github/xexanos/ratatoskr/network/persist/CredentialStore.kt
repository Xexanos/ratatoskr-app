/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import io.github.xexanos.ratatoskr.network.domain.Credential

/**
 * The credential-shaped seam the app module depends on for auth state (SPEC section 5): it can
 * ask whether a credential is stored and discard it, and nothing more. The app never sees the
 * token pair behind it, so the /v2 cutover - which collapses that pair into one non-expiring
 * Ratatoskr token - changes only the [TokenAccess] implementation below this line, not a single
 * app-module caller.
 */
interface CredentialStore {

    /** The stored credential, or null when signed out. Opaque: callers only compare it to null. */
    suspend fun credential(): Credential?

    /**
     * Whether a credential is stored - the launch-routing "is the user signed in?" check
     * (SPEC section 13). Presence mirrors [credential], so it stays exact across the cutover.
     */
    suspend fun hasCredential(): Boolean = credential() != null

    /** Discards the stored credential (sign-out, or a forced re-login). */
    suspend fun clear()
}
