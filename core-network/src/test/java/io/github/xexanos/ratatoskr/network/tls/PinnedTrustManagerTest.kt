/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class PinnedTrustManagerTest {

    // The self-signed test certificates below are never in the JVM's default trust store, so
    // platform validation always fails here and the pin decides - the TOFU path this class
    // exists for. A platform-trusted case would need a certificate chaining to a real CA in
    // that store, which isn't practical to mint in a unit test; that branch is exercised
    // indirectly (FactoryTlsPinComponentTest documents the same gap for the instrumented level).

    private fun chainOf(certificate: HeldCertificate): Array<X509Certificate> =
        arrayOf(certificate.certificate)

    @Test
    fun `platform failure with no confirmed fingerprint throws`() {
        val served = HeldCertificate.Builder().build()
        val trustManager = PinnedTrustManager(expectedFingerprint = null)

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(chainOf(served), "RSA")
        }
    }

    @Test
    fun `platform failure with a matching confirmed fingerprint does not throw`() {
        val served = HeldCertificate.Builder().build()
        val trustManager = PinnedTrustManager(expectedFingerprint = Fingerprints.sha256(served.certificate))

        trustManager.checkServerTrusted(chainOf(served), "RSA")
    }

    @Test
    fun `platform failure with a non-matching confirmed fingerprint throws`() {
        val served = HeldCertificate.Builder().build()
        val other = HeldCertificate.Builder().build()
        val trustManager = PinnedTrustManager(expectedFingerprint = Fingerprints.sha256(other.certificate))

        assertThrows(CertificateException::class.java) {
            trustManager.checkServerTrusted(chainOf(served), "RSA")
        }
    }

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
