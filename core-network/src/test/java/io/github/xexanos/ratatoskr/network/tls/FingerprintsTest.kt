/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FingerprintsTest {

    @Test
    fun `sha256 is the lowercase colon-separated SHA-256 of the certificate DER encoding`() {
        val certificate = HeldCertificate.Builder().build().certificate
        val expected = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { byte -> "%02x".format(byte) }

        assertEquals(expected, Fingerprints.sha256(certificate))
    }

    @Test
    fun `matches ignores case and separators`() {
        assertTrue(Fingerprints.matches("AA:BB:CC", "aabbcc"))
        assertTrue(Fingerprints.matches("AA:BB:CC", "aa:bb:cc"))
        assertTrue(Fingerprints.matches("aa-bb-cc", "AABBCC"))
    }

    @Test
    fun `matches rejects a genuinely different fingerprint`() {
        assertFalse(Fingerprints.matches("aa:bb:cc", "aa:bb:cd"))
    }
}
