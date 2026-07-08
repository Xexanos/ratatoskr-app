/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use trust manager (SPEC section 6): platform validation first - which
 * covers a reverse proxy with a publicly trusted certificate - then, only if that fails,
 * the user-confirmed pinned SHA-256 fingerprint. Trusting neither is a hard failure.
 *
 * Scope of the "changed certificate is rejected" guarantee (SPEC section 6, deliberate
 * trade-off): it holds for the self-signed / local-CA deployment, where the platform chain
 * fails and the stored pin therefore always decides. When the server presents a *publicly
 * trusted* certificate the platform chain validates and the pin is not consulted, so a
 * different but validly issued certificate for the same host is accepted without
 * re-confirmation - trust is delegated to the public CA. Enforcing the pin there too would
 * reject every routine renewal (e.g. Let's Encrypt) and force a manual re-trust each time.
 *
 * Runs entirely on the platform TLS stack, so it survives a reproducible F-Droid build; no
 * build-time network-security-config pin is involved.
 */
class PinnedTrustManager(
    private val expectedFingerprint: String?,
) : X509TrustManager {

    private val platform: X509TrustManager = defaultTrustManager()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        platform.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        // Reject an empty chain ourselves, before delegating: the platform trust manager throws
        // IllegalArgumentException (a RuntimeException) for a zero-length chain, which would
        // escape the CertificateException catch below and surface to callers as an opaque error
        // instead of the CertificateException every TOFU trust failure is contracted to throw.
        if (chain.isEmpty()) {
            throw CertificateException("Server presented an empty certificate chain.")
        }
        try {
            platform.checkServerTrusted(chain, authType)
            return
        } catch (platformFailure: CertificateException) {
            val pin = expectedFingerprint
                ?: throw CertificateException(
                    "Server certificate is not trusted and no fingerprint has been confirmed yet.",
                    platformFailure,
                )
            val leaf = chain.first()
            if (!Fingerprints.matches(Fingerprints.sha256(leaf), pin)) {
                throw CertificateException(
                    "Server certificate fingerprint does not match the confirmed one. " +
                        "It may have changed; re-confirm it in settings if this is expected.",
                    platformFailure,
                )
            }
            // Fingerprint matches the user-confirmed pin: trusted.
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    companion object {
        fun socketFactory(trustManager: X509TrustManager): SSLSocketFactory {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), java.security.SecureRandom())
            return context.socketFactory
        }

        private fun defaultTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
