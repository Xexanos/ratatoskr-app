/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * Pin-aware hostname verification, the counterpart of [PinnedTrustManager] (SPEC section 6).
 *
 * When the served leaf certificate matches the user-confirmed fingerprint, the hostname is
 * ignored: the user confirmed *that exact certificate*, so it is trustworthy at any address -
 * which is what lets the app reach a self-signed server by a LAN IP (or the E2E emulator's
 * 10.0.2.2) that appears in no certificate SAN.
 *
 * Everything else stays host-bound through [defaultVerifier]. That matters because
 * [PinnedTrustManager] tries platform validation FIRST and consults the pin only on its
 * failure: on the platform-trusted path (reverse proxy with a public CA) the hostname
 * verifier is the only thing binding the certificate to the host. Dropping it there would
 * let an on-path attacker connect with a validly issued certificate for their own domain.
 * The same holds for a client built without a confirmed fingerprint.
 */
class PinnedHostnameVerifier(
    private val expectedFingerprint: String?,
    // OkHttp's standard verifier (OkHostnameVerifier), obtained through public API - the
    // singleton itself lives in an internal package we must not reference.
    private val defaultVerifier: HostnameVerifier = OkHttpClient().hostnameVerifier,
) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        val pinCarries = leaf != null && expectedFingerprint != null &&
            Fingerprints.matches(Fingerprints.sha256(leaf), expectedFingerprint)
        return pinCarries || defaultVerifier.verify(hostname, session)
    }
}
