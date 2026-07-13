/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext

class PinnedHostnameVerifierTest {

    // The platform-trusted path - a validly chained certificate for the WRONG host must stay
    // rejected - cannot be reached by the instrumented TLS suite (the test CA is not in the
    // system trust store, see FactoryTlsPinComponentTest). At this level the trust decision is
    // out of the picture entirely: the verifier only sees the session, so the same cases cover
    // both paths. What matters is that ONLY a leaf matching the confirmed pin lifts the host
    // binding; anything else falls through to OkHttp's standard verifier.

    private val served = HeldCertificate.Builder().addSubjectAlternativeName("example.com").build()
    private val other = HeldCertificate.Builder().addSubjectAlternativeName("example.com").build()

    private fun sessionServing(certificate: HeldCertificate): SSLSession =
        FakeSslSession { arrayOf(certificate.certificate) }

    @Test
    fun `a leaf matching the confirmed pin is accepted at any hostname`() {
        val verifier = PinnedHostnameVerifier(Fingerprints.sha256(served.certificate))

        // The LAN-IP case: the confirmed certificate carries the connection, its SAN does not.
        assertTrue(verifier.verify("192.168.1.20", sessionServing(served)))
    }

    @Test
    fun `a non-matching leaf on a non-matching hostname is rejected`() {
        val verifier = PinnedHostnameVerifier(Fingerprints.sha256(other.certificate))

        // The attack case: a certificate that is not the confirmed one (on device it would be
        // platform-trusted for the attacker's own domain) must stay bound to its hostname.
        assertFalse(verifier.verify("192.168.1.20", sessionServing(served)))
    }

    @Test
    fun `without a confirmed pin the client stays host-bound`() {
        val verifier = PinnedHostnameVerifier(expectedFingerprint = null)

        assertFalse(verifier.verify("192.168.1.20", sessionServing(served)))
    }

    @Test
    fun `without a confirmed pin a hostname matching the SAN is accepted`() {
        val verifier = PinnedHostnameVerifier(expectedFingerprint = null)

        // Delegation to OkHttp's standard verifier works: the ordinary platform-trusted
        // deployment keeps connecting exactly as before.
        assertTrue(verifier.verify("example.com", sessionServing(served)))
    }

    @Test
    fun `an unverified peer fails closed on a non-matching hostname`() {
        val verifier = PinnedHostnameVerifier(Fingerprints.sha256(served.certificate))
        val unverified = FakeSslSession { throw SSLPeerUnverifiedException("peer not verified") }

        assertFalse(verifier.verify("192.168.1.20", unverified))
    }

    /**
     * The verifier reads nothing but the peer certificates, both directly and through OkHttp's
     * standard verifier, so that is the only [SSLSession] member a fake needs to implement.
     * Every other accessor fails loudly rather than returning a misleading stub value.
     */
    private class FakeSslSession(
        private val peerCertificates: () -> Array<Certificate>,
    ) : SSLSession {
        override fun getPeerCertificates(): Array<Certificate> = peerCertificates()

        override fun getId(): ByteArray = unused()
        override fun getSessionContext(): SSLSessionContext = unused()
        override fun getCreationTime(): Long = unused()
        override fun getLastAccessedTime(): Long = unused()
        override fun invalidate(): Unit = unused()
        override fun isValid(): Boolean = unused()
        override fun putValue(name: String?, value: Any?): Unit = unused()
        override fun getValue(name: String?): Any = unused()
        override fun removeValue(name: String?): Unit = unused()
        override fun getValueNames(): Array<String> = unused()
        override fun getLocalCertificates(): Array<Certificate> = unused()

        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = unused()

        override fun getPeerPrincipal(): Principal = unused()
        override fun getLocalPrincipal(): Principal = unused()
        override fun getCipherSuite(): String = unused()
        override fun getProtocol(): String = unused()
        override fun getPeerHost(): String = unused()
        override fun getPeerPort(): Int = unused()
        override fun getPacketBufferSize(): Int = unused()
        override fun getApplicationBufferSize(): Int = unused()

        private fun unused(): Nothing =
            throw UnsupportedOperationException("not expected to be read by the verifier")
    }
}
