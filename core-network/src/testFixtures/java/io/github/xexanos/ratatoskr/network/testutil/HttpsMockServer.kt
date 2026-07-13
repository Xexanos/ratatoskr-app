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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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

    /** Base URL WITHOUT the `/v1/` suffix - the factory appends it. Valid once the rule ran. */
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    /** Registers a client to be closed when the test ends, and returns it. */
    fun track(client: RatatoskrClient): RatatoskrClient {
        clients += client
        return client
    }

    override fun before() {
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(served)
            .build()
        // Wrap the server socket factory (see PlainSessionSslSocketFactory): okhttp 5's
        // mockwebserver3 reads the client-requested TLS server names for every request, and on
        // Android 8.0 (API 26) that read throws and crashes the instrumentation process. The
        // wrapper sidesteps it without changing the TLS the tests actually exercise.
        val sslSocketFactory = PlainSessionSslSocketFactory(serverCertificates.sslSocketFactory())
        server.useHttps(sslSocketFactory, /* tunnelProxy = */ false)
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
        // canonicalHostName is what MockWebServer.url() reports for the loopback address; using
        // it as the certificate SAN keeps the served cert valid for the URL the tests call.
        private val loopbackHost: String = InetAddress.getByName("localhost").canonicalHostName

        // Minted once per class load, not per fixture instance: JUnit4 constructs a fresh test
        // instance (and with it this fixture) for every @Test method, and the certificate
        // depends on no instance state - regenerating it per test is pure repeat keygen work.
        private val served: HeldCertificate =
            HeldCertificate.Builder().addSubjectAlternativeName(loopbackHost).build()
    }
}

/**
 * Wraps a server [SSLSocketFactory] so every accepted TLS socket reports a plain [SSLSession]
 * instead of an [ExtendedSSLSession] (see [PlainSessionSslSocket]).
 *
 * Why this exists: okhttp 5's mockwebserver3 records the client-requested TLS server names for
 * every request (`RecordedRequest.handshakeServerNames`). On Android it reads them via
 * `Platform.getHandshakeServerNames`, which casts the socket's session to [ExtendedSSLSession]
 * and calls `getRequestedServerNames()`. On API 26 (Android 8.0) Conscrypt returns null there,
 * and the Kotlin non-null check throws on a MockWebServer background thread - crashing the
 * whole instrumentation process on the first HTTPS request. (On API 36 the same call returns an
 * empty list, so the crash is API-26-only; okhttp 4's MockWebServer never read this at all.)
 *
 * Hiding the [ExtendedSSLSession] makes okhttp take its empty-list branch on every API level.
 * The handshake and all I/O still run on the real delegate socket, so the TLS the tests
 * exercise (Android's trust manager, the fingerprint pin, hostname verification) is unchanged.
 */
private class PlainSessionSslSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {

    private fun wrap(socket: Socket): Socket =
        if (socket is SSLSocket) PlainSessionSslSocket(socket) else socket

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        wrap(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket =
        wrap(delegate.createSocket(host, port))

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = wrap(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        wrap(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = wrap(delegate.createSocket(address, port, localAddress, localPort))
}

/**
 * An [SSLSocket] that forwards everything to [delegate] except [getSession]/[getHandshakeSession],
 * which return a session that is deliberately NOT an [ExtendedSSLSession]. See
 * [PlainSessionSslSocketFactory] for why. Everything else is plain delegation, so the real
 * handshake and encrypted I/O are untouched.
 */
private class PlainSessionSslSocket(
    private val delegate: SSLSocket,
) : SSLSocket() {

    private fun strip(session: SSLSession?): SSLSession? =
        session?.let { if (it is ExtendedSSLSession) PlainSslSession(it) else it }

    override fun getSession(): SSLSession = strip(delegate.session)!!

    override fun getHandshakeSession(): SSLSession? = strip(delegate.handshakeSession)

    // SSLSocket's base implementations of these throw UnsupportedOperationException; okhttp's
    // JVM platform reads the negotiated ALPN protocol through them, so they must be delegated
    // or every HTTPS request fails. (Only reached on the JVM / API 29+; okhttp's Android
    // platform uses reflection on API 26, so these are never called there.)
    override fun getApplicationProtocol(): String? = delegate.applicationProtocol
    override fun getHandshakeApplicationProtocol(): String? = delegate.handshakeApplicationProtocol

    // --- pure delegation below (explicit getX/setX calls to avoid Kotlin property-name
    //     ambiguity for acronyms like SSL/OOB) --------------------------------------------

    override fun getSupportedCipherSuites(): Array<String> = delegate.getSupportedCipherSuites()
    override fun getEnabledCipherSuites(): Array<String> = delegate.getEnabledCipherSuites()
    override fun setEnabledCipherSuites(suites: Array<String>?) =
        delegate.setEnabledCipherSuites(suites)

    override fun getSupportedProtocols(): Array<String> = delegate.getSupportedProtocols()
    override fun getEnabledProtocols(): Array<String> = delegate.getEnabledProtocols()
    override fun setEnabledProtocols(protocols: Array<String>?) =
        delegate.setEnabledProtocols(protocols)

    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) =
        delegate.addHandshakeCompletedListener(listener)

    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) =
        delegate.removeHandshakeCompletedListener(listener)

    override fun startHandshake() = delegate.startHandshake()

    override fun setUseClientMode(mode: Boolean) = delegate.setUseClientMode(mode)
    override fun getUseClientMode(): Boolean = delegate.useClientMode
    override fun setNeedClientAuth(need: Boolean) = delegate.setNeedClientAuth(need)
    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
    override fun setWantClientAuth(want: Boolean) = delegate.setWantClientAuth(want)
    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
    override fun setEnableSessionCreation(flag: Boolean) = delegate.setEnableSessionCreation(flag)
    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation

    override fun getSSLParameters(): SSLParameters = delegate.getSSLParameters()
    override fun setSSLParameters(params: SSLParameters?) = delegate.setSSLParameters(params)

    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()

    override fun connect(endpoint: SocketAddress?) = delegate.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)

    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): SocketChannel? = delegate.channel

    override fun setTcpNoDelay(on: Boolean) = delegate.setTcpNoDelay(on)
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = delegate.setSoLinger(on, linger)
    override fun getSoLinger(): Int = delegate.soLinger
    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) = delegate.setOOBInline(on)
    override fun getOOBInline(): Boolean = delegate.getOOBInline()
    override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) = delegate.setSendBufferSize(size)
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) = delegate.setReceiveBufferSize(size)
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) = delegate.setKeepAlive(on)
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) = delegate.setTrafficClass(tc)
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) = delegate.setReuseAddress(on)
    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) =
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)

    override fun toString(): String = delegate.toString()
}

/**
 * An [SSLSession] that forwards every call to [delegate] but is NOT an [ExtendedSSLSession], so
 * okhttp's `getHandshakeServerNames` skips the Android read that crashes on API 26. Peer
 * certificates, cipher suite and the rest still come straight from the real session.
 */
private class PlainSslSession(delegate: SSLSession) : SSLSession by delegate
