/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.junit.Assert.assertThrows
import org.junit.Test

class PinnedTrustManagerTest {

    /**
     * An empty chain must fail as a [CertificateException] - the type every TOFU trust failure is
     * contracted to throw and the only type [io.github.xexanos.ratatoskr.network.api.RatatoskrClient]
     * maps to CertificateUntrusted. Guarding it ourselves is what makes this hold: delegating to
     * the platform trust manager would raise IllegalArgumentException (a RuntimeException), which
     * escapes the CertificateException catch and surfaces to callers as an opaque Unexpected error.
     * The guarantee must not depend on whether a pin has been confirmed, so both cases are checked.
     */
    @Test
    fun `empty certificate chain throws CertificateException regardless of pin`() {
        val emptyChain = emptyArray<X509Certificate>()

        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(expectedFingerprint = null).checkServerTrusted(emptyChain, "RSA")
        }
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager(expectedFingerprint = "AA:BB").checkServerTrusted(emptyChain, "RSA")
        }
    }
}
