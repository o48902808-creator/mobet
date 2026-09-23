package ai.arena.mobet.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the crypto contract that [SecretStore] relies on: name-bound AAD, graceful failure on
 * tampered or swapped payloads, and uninterrupted reads for pre-binding (no-AAD) secrets.
 */
class SecretStoreCodecTest {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripPreservesTheValue() {
        val payload = SecretStoreCodec.encrypt("account_pin", "5up3r-SECRET · £", key)
        val decoded = SecretStoreCodec.decrypt("account_pin", payload, key)
        assertEquals("5up3r-SECRET · £", decoded?.value)
        assertFalse(decoded!!.needsReencryption)
    }

    @Test
    fun blobEncryptedForOneNameDoesNotOpenUnderAnother() {
        val payload = SecretStoreCodec.encrypt("personal_pin", "pin-1", key)
        assertNull(SecretStoreCodec.decrypt("work_pin", payload, key))
    }

    @Test
    fun ciphertextSwapBetweenEntriesIsRejected() {
        val a = SecretStoreCodec.encrypt("alpha", "a-value", key)
        val b = SecretStoreCodec.encrypt("beta", "b-value", key)
        // Simulates the two SharedPreferences rows being swapped behind the app's back.
        assertNull(SecretStoreCodec.decrypt("alpha", b, key))
        assertNull(SecretStoreCodec.decrypt("beta", a, key))
    }

    @Test
    fun tamperedCiphertextFailsClosed() {
        val raw = Base64.getDecoder().decode(SecretStoreCodec.encrypt("k", "value", key))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        assertNull(SecretStoreCodec.decrypt("k", Base64.getEncoder().encodeToString(raw), key))
    }

    @Test
    fun garbageAndTruncatedPayloadsAreUnreadable() {
        assertNull(SecretStoreCodec.decrypt("k", "not-base64-at-all!!", key))
        assertNull(SecretStoreCodec.decrypt("k", "", key))
        val short = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        assertNull(SecretStoreCodec.decrypt("k", short, key))
        val full = SecretStoreCodec.encrypt("k", "value", key)
        assertNull(SecretStoreCodec.decrypt("k", full.take(full.length - 8), key))
    }

    @Test
    fun legacyUnboundPayloadsStillReadAndAreFlaggedForUpgrade() {
        // Reproduces the layout written before name-binding existed: GCM with no AAD.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val legacy = Base64.getEncoder().encodeToString(
            cipher.iv + cipher.doFinal("old-secret".toByteArray(Charsets.UTF_8))
        )
        val decoded = SecretStoreCodec.decrypt("legacy_name", legacy, key)
        assertEquals("old-secret", decoded?.value)
        assertTrue(decoded!!.needsReencryption)
    }

    @Test
    fun wrongKeyCannotReadEitherLayout() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val payload = SecretStoreCodec.encrypt("k", "value", key)
        assertNull(SecretStoreCodec.decrypt("k", payload, other))
    }
}
