/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import io.github.xexanos.ratatoskr.network.domain.CertificateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Fetches a server's leaf certificate so the connect screen can show its fingerprint for
 * trust-on-first-use (SPEC section 6). The client built here trusts everything and MUST
 * only ever be used to read the certificate chain -- never for real API traffic.
 */
class CertificateInspector {

    suspend fun inspect(baseUrl: String): CertificateInfo = withContext(Dispatchers.IO) {
        val trustAll = TrustAllManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            // The host is the user's own server; TOFU replaces hostname/CA validation here.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        try {
            val request = Request.Builder().url(healthUrl(baseUrl)).build()
            client.newCall(request).execute().use { response ->
                val peer = response.handshake?.peerCertificates
                    ?: error("No TLS handshake; is the server serving HTTPS?")
                val leaf = peer.firstOrNull() as? X509Certificate
                    ?: error("Server presented no X.509 certificate.")
                leaf.toCertificateInfo()
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun healthUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return "$trimmed/health"
    }

    private fun X509Certificate.toCertificateInfo(): CertificateInfo = CertificateInfo(
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        notBefore = OffsetDateTime.ofInstant(notBefore.toInstant(), ZoneOffset.UTC),
        notAfter = OffsetDateTime.ofInstant(notAfter.toInstant(), ZoneOffset.UTC),
        sha256Fingerprint = Fingerprints.sha256(this),
    )

    private class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
