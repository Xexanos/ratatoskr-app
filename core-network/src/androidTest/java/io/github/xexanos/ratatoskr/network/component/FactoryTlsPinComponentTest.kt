/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.component

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xexanos.ratatoskr.network.FakeTokenAccess
import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.api.RatatoskrClientFactory
import io.github.xexanos.ratatoskr.network.domain.ApiResult
import io.github.xexanos.ratatoskr.network.domain.RatatoskrError
import io.github.xexanos.ratatoskr.network.testutil.HttpsMockServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SPEC section 9, bullet (b): the TLS pin (section 6) against Android's REAL trust manager and
 * TLS provider. The served certificate is self-signed and not in the system trust store, so the
 * platform check fails and the user-confirmed fingerprint decides - the primary self-signed /
 * local-CA deployment.
 *
 * The publicly-trusted-cert branch of the section-6 trade-off (platform validates, so the pin is
 * skipped) is deliberately NOT covered here: the trust manager consults only the system trust
 * store, which a test-minted CA is not in, so a self-signed MockWebServer cannot reach that path.
 * It stays a documented gap (SPEC section 9).
 *
 * The fixture's certificate SAN deliberately does not match the host it serves on, so these
 * tests also prove the PIN path ignores the certificate hostname (SPEC section 6). The
 * platform-trusted path stays host-bound through the pin-aware verifier; being unreachable
 * here (same reason as above), that binding is covered by PinnedHostnameVerifierTest on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class FactoryTlsPinComponentTest {

    @get:Rule val https = HttpsMockServer()

    private fun client(fingerprint: String?): RatatoskrClient =
        https.track(RatatoskrClientFactory.create(https.baseUrl, fingerprint, FakeTokenAccess("a0", "r0")))

    @Test
    fun aMatchingPinnedFingerprintConnects() = runBlocking {
        https.enqueueJson("[]")

        val result = client(https.fingerprint).listSpeakers()

        assertTrue("expected Success, was $result", result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun aPinnedConnectionIgnoresTheCertificateHostname() = runBlocking {
        // HttpsMockServer serves a certificate whose SAN never matches the loopback host it runs
        // on, yet the connection succeeds: the confirmed pin carries the connection, so the
        // pin-aware hostname verifier ignores the hostname (SPEC section 6). Guards against
        // losing that - a hostname-bound pin path would break connecting to a server by a LAN IP
        // (and the E2E emulator's 10.0.2.2). Also proves, through the real Conscrypt stack, that
        // the verifier can read the peer certificates it needs for the pin comparison.
        https.enqueueJson("[]")

        val result = client(https.fingerprint).listSpeakers()

        assertTrue("expected Success despite a non-matching cert SAN, was $result", result is ApiResult.Success)
    }

    @Test
    fun aChangedFingerprintIsRejectedAsCertificateUntrusted() = runBlocking {
        // Handshake fails before any request, so nothing needs to be enqueued.
        val result = client(https.wrongFingerprint).listSpeakers()

        val error = (result as ApiResult.Failure).error
        assertTrue("expected CertificateUntrusted, was $error", error is RatatoskrError.CertificateUntrusted)
    }

    @Test
    fun anUntrustedServerWithNoConfirmedFingerprintIsRejected() = runBlocking {
        val result = client(fingerprint = null).listSpeakers()

        val error = (result as ApiResult.Failure).error
        assertTrue("expected CertificateUntrusted, was $error", error is RatatoskrError.CertificateUntrusted)
    }
}
