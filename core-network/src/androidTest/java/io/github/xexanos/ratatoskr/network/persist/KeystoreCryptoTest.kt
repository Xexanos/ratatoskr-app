/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.network.persist

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [KeystoreCrypto] against the real Android Keystore (instrumented: there is no Keystore on
 * the JVM). The decrypt contract is "null on anything unreadable, never a crash" (SPEC
 * section 5) - a stored value that cannot be decrypted must lead to a re-login, so every
 * malformed-input case here asserts null.
 *
 * The malformed-payload tests encrypt something first: decrypt returns null early when the
 * key alias does not exist yet, so without that step they would pass even if the parsing
 * paths they target crashed.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoTest {

    // Fixed alias, unique to this class: instrumentation tests share a process, and Keystore
    // entries survive across runs, so reruns simply reuse the key.
    private val crypto = KeystoreCrypto(keyAlias = "ratatoskr.test.KeystoreCryptoTest")

    @Test
    fun roundtripReturnsThePlaintext() {
        assertEquals("token-value", crypto.decrypt(crypto.encrypt("token-value")))
    }

    @Test
    fun payloadTruncatedBelowTheIvLengthReturnsNull() {
        crypto.encrypt("mint the key")
        // Valid Base64, but decodes to fewer bytes than the 12-byte GCM IV. Regression test:
        // this used to throw IndexOutOfBoundsException from copyOfRange instead of returning
        // null (an IndexOutOfBoundsException is not a GeneralSecurityException).
        val truncated = Base64.encodeToString(ByteArray(4), Base64.NO_WRAP)
        assertNull(crypto.decrypt(truncated))
    }

    @Test
    fun payloadWithIvButNoCiphertextReturnsNull() {
        crypto.encrypt("mint the key")
        // Exactly the IV, zero ciphertext bytes: GCM rejects the missing auth tag.
        val ivOnly = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
        assertNull(crypto.decrypt(ivOnly))
    }

    @Test
    fun malformedBase64ReturnsNull() {
        crypto.encrypt("mint the key")
        assertNull(crypto.decrypt("%%% not base64 %%%"))
    }

    @Test
    fun tamperedCiphertextReturnsNull() {
        val encoded = crypto.encrypt("token-value")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0x01).toByte()
        assertNull(crypto.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }

    @Test
    fun decryptWithoutTheKeyReturnsNull() {
        // A dedicated alias that this test guarantees absent: the "key is gone" path must
        // yield null (and must NOT mint a fresh key - that was the crash-on-launch bug).
        val alias = "ratatoskr.test.KeystoreCryptoTest.absent"
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        val fresh = KeystoreCrypto(keyAlias = alias)
        assertNull(fresh.decrypt(crypto.encrypt("token-value")))
    }
}
