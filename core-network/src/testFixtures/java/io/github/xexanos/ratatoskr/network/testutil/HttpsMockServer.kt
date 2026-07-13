/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.testutil

import io.github.xexanos.ratatoskr.network.api.RatatoskrClient
import io.github.xexanos.ratatoskr.network.tls.Fingerprints
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.rules.ExternalResource

/**
 * A [MockWebServer] served over HTTPS with a self-signed certificate, for the instrumented
 * integration tests (SPEC section 9). It exists so tests can drive the real client assembly
 * (`RatatoskrClientFactory.create`) over TLS, exercising Android's actual trust manager and
 * TLS provider rather than a JVM stand-in.
 *
 * The served certificate is NOT in any system trust store, so the production trust manager's
 * platform check fails and the user-confirmed fingerprint pin decides - which is the primary
 * self-signed / local-CA deployment (SPEC section 6). Pass [fingerprint] as the factory's pin
 * to connect; [wrongFingerprint] to simulate a changed certificate.
 *
 * The certificate's SAN is the loopback host name MockWebServer reports, so the production
 * hostname verifier (which the factory does not disable) is satisfied.
 *
 * Use as a JUnit rule: it owns the whole per-test lifecycle - the server starts before each
 * test, and afterwards every client registered via [track] is closed and the server shut
 * down. Test classes declare `@get:Rule val https = HttpsMockServer()` and build clients as
 * `https.track(RatatoskrClientFactory.create(...))`; no @Before/@After boilerplate.
 */
class HttpsMockServer : ExternalResource() {

    val server = MockWebServer()

    private val clients = mutableListOf<RatatoskrClient>()

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

    /**
     * Base URL WITHOUT the `/v1/` suffix - the factory appends it. Valid once the rule ran.
     *
     * The host is forced to [SERVER_HOST] (a DNS name) rather than taken verbatim from
     * [MockWebServer.url], which on the emulator resolves the loopback to an IP literal. A
     * DNS host is what makes the client send a TLS SNI extension; without it okhttp 5's
     * mockwebserver3 crashes reading server-side SNI (see [SERVER_HOST]).
     */
    val baseUrl: String get() =
        server.url("/").newBuilder().host(SERVER_HOST).build().toString().trimEnd('/')

    /** Registers a client to be closed when the test ends, and returns it. */
    fun track(client: RatatoskrClient): RatatoskrClient {
        clients += client
        return client
    }

    override fun before() {
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(served)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), /* tunnelProxy = */ false)
        server.start()
    }

    override fun after() {
        clients.forEach { it.close() }
        clients.clear()
        server.shutdown()
    }

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

    private companion object {
        // The DNS name the tests connect to AND the certificate's SAN, so hostname verification
        // passes. It must be a DNS name (not an IP literal): okhttp only emits a TLS SNI
        // extension for non-IP hosts, and okhttp 5's mockwebserver3 records the server-side SNI
        // for every request (RecordedRequest.handshakeServerNames). On Android, reading that from
        // a session with no requested names throws ("getRequestedServerNames(...) must not be
        // null"), which crashes the whole instrumentation process. "localhost" resolves to the
        // loopback MockWebServer binds to, so it both reaches the server and triggers SNI.
        private const val SERVER_HOST = "localhost"

        // Minted once per class load, not per fixture instance: JUnit4 constructs a fresh test
        // instance (and with it this fixture) for every @Test method, and the certificate
        // depends on no instance state - regenerating it per test is pure repeat keygen work.
        private val served: HeldCertificate =
            HeldCertificate.Builder().addSubjectAlternativeName(SERVER_HOST).build()
    }
}
