/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** SHA-256 fingerprints of TLS certificates, in the human-readable colon-separated form. */
object Fingerprints {

    /** Lowercase, colon-separated SHA-256 of the certificate's DER encoding. */
    fun sha256(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { byte -> "%02x".format(byte) }
    }

    /** Compare two fingerprints ignoring case and separators, so formatting never causes a false mismatch. */
    fun matches(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(fingerprint: String): String =
        fingerprint.lowercase().filter { it.isLetterOrDigit() }
}
