/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The trust chip's host, derived from the stored base URL. Its whole job is to be recognisable
 * as the user's own machine while leaking nothing else about the URL, so both halves are pinned
 * here: the port survives when it is not 443, and the scheme, path and query never appear.
 */
class ServerDisplayHostTest {

    @Test
    fun `an implicit https port is left off`() {
        assertEquals("ratatoskr.home.arpa", serverDisplayHost("https://ratatoskr.home.arpa"))
    }

    @Test
    fun `an explicit 443 is left off too`() {
        assertEquals("ratatoskr.home.arpa", serverDisplayHost("https://ratatoskr.home.arpa:443"))
    }

    @Test
    fun `a non-default port is kept`() {
        // Self-hosted servers on an odd port are the common case; dropping it would hide the
        // half of the address the user recognises their own machine by.
        assertEquals("ratatoskr.home.arpa:8443", serverDisplayHost("https://ratatoskr.home.arpa:8443"))
    }

    @Test
    fun `the scheme, path and query never appear`() {
        assertEquals(
            "ratatoskr.home.arpa:8443",
            serverDisplayHost("https://ratatoskr.home.arpa:8443/v2?debug=1"),
        )
    }

    @Test
    fun `an IP address and port come through unchanged`() {
        assertEquals("192.0.2.10:8443", serverDisplayHost("https://192.0.2.10:8443"))
    }

    @Test
    fun `a URL with no host at all yields null`() {
        // The screen renders null as no chip - never a placeholder or an "unknown host" string.
        assertNull(serverDisplayHost("ratatoskr.home.arpa:8443"))
    }

    @Test
    fun `an unparseable URL yields null`() {
        assertNull(serverDisplayHost("https://not a host"))
    }

    @Test
    fun `a blank URL yields null`() {
        assertNull(serverDisplayHost(""))
    }
}
