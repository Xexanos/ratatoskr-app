/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.integration

import io.github.xexanos.ratatoskr.network.tls.Fingerprints
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress

/**
 * A [MockWebServer] served over HTTPS with a self-signed certificate, for the instrumented
 * integration tests (SPEC section 9). It exists so tests can drive the real client assembly
 * (`RatatoskrClientFactory.create`) over TLS, exercising Android's actual trust manager and
 * TLS provider rather than a JVM stand-in.
 *
 * The served certificate is minted per instance and is NOT in any system trust store, so the
 * production trust manager's platform check fails and the user-confirmed fingerprint pin
 * decides - which is the primary self-signed / local-CA deployment (SPEC section 6). Pass
 * [fingerprint] as the factory's pin to connect; [wrongFingerprint] to simulate a changed
 * certificate.
 *
 * The certificate's SAN is the loopback host name MockWebServer reports, so the production
 * hostname verifier (which the factory does not disable) is satisfied.
 */
class HttpsMockServer {

    val server = MockWebServer()

    // canonicalHostName is what MockWebServer.url() reports for the loopback address; using it
    // as the certificate SAN keeps the served cert valid for the URL the tests actually call.
    private val loopbackHost: String = InetAddress.getByName("localhost").canonicalHostName

    private val served: HeldCertificate =
        HeldCertificate.Builder().addSubjectAlternativeName(loopbackHost).build()

    /** SHA-256 of the served leaf, in the exact form [Fingerprints.sha256] produces. */
    val fingerprint: String get() = Fingerprints.sha256(served.certificate)

    /**
     * A well-formed SHA-256 fingerprint that cannot match the served certificate, standing in
     * for a changed/rotated one. A constant suffices: the pin comparison is a normalized string
     * equality ([Fingerprints.matches]), never a proof that the pin belongs to a real
     * certificate - so minting a second certificate would buy nothing.
     */
    val wrongFingerprint: String =
        "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:" +
            "00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff"

    /** Base URL WITHOUT the `/v1/` suffix - the factory appends it. Valid after [start]. */
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun start() {
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(served)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), /* tunnelProxy = */ false)
        server.start()
    }

    fun shutdown() = server.shutdown()

    fun enqueueJson(body: String, code: Int = 200) = server.enqueue(
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body.trimIndent()),
    )

    fun enqueue204() = server.enqueue(MockResponse().setResponseCode(204))

    fun dispatch(block: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = block(request)
        }
    }

    fun takeRequest(): RecordedRequest = server.takeRequest()
}
