/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.data

import io.github.xexanos.ratatoskr.network.FakeConnectionStore
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.domain.AuthUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one-time /v1 -> /v2 migration (SPEC section 5, issue #121): the first launch after the
 * update finds the stranded Audiobookshelf tokens, discards them, and queues the "app updated"
 * sign-in notice - while server, certificate, and username survive untouched around it.
 */
class ConnectionManagerTest {

    private fun manager(tokens: FakeTokenAccess) = ConnectionManager(
        FakeConnectionStore(baseUrl = "https://server.example:8443", fingerprint = "AA:BB"),
        tokens,
    )

    @Test
    fun `the first launch after the update discards the v1 tokens and queues the notice`() = runBlocking {
        // A pre-update install: legacy tokens plus the remembered user, but no /v2 token.
        val tokens = FakeTokenAccess(user = AuthUser("7", "alex"), legacyTokens = true)
        val manager = manager(tokens)

        manager.migrateFromV1()

        // Signed out, but the username survives for the pre-fill; the one-time notice is queued
        // for the sign-in screen and consumed with the read.
        assertFalse(manager.credentials.hasCredential())
        assertEquals("alex", manager.prefillUsername())
        assertEquals(SignInPrompt.AppUpdated, manager.consumeSignInPrompt())
        assertNull(manager.consumeSignInPrompt())
    }

    @Test
    fun `a launch with nothing to migrate queues no notice`() = runBlocking {
        val manager = manager(FakeTokenAccess())

        manager.migrateFromV1()

        assertNull(manager.consumeSignInPrompt())
    }

    @Test
    fun `a later launch after the migration is a no-op`() = runBlocking {
        val tokens = FakeTokenAccess(user = AuthUser("7", "alex"), legacyTokens = true)
        val manager = manager(tokens)
        manager.migrateFromV1()
        manager.consumeSignInPrompt() // the first launch showed its notice

        manager.migrateFromV1()

        assertNull(manager.consumeSignInPrompt())
    }

    @Test
    fun `a signed-in v2 launch keeps its credential`() = runBlocking {
        val tokens = FakeTokenAccess(token = "ratatoskr-token", user = AuthUser("7", "alex"))
        val manager = manager(tokens)

        manager.migrateFromV1()

        assertEquals(true, manager.credentials.hasCredential())
        assertNull(manager.consumeSignInPrompt())
    }
}
