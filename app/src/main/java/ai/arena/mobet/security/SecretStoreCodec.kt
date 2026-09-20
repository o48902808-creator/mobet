package ai.arena.mobet.security

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pure payload codec for [SecretStore]: AES-GCM with the secret's *name* bound as
 * authenticated data, over a `iv || ciphertext` blob in standard Base64.
 *
 * Why the name is authenticated: GCM's tag verifies that a payload is intact, but without
 * associated data it cannot verify *which* secret it is. Two blobs inside the same store can
 * be swapped undetected — a workflow that typed `{{secret:personal_pin}}` would silently
 * receive whatever had been stored under `{{secret:work_pin}}`. Binding the name as AAD makes
 * a swapped blob fail verification exactly like a corrupted one. [EncryptedStateStore] already
 * does the same per namespace; secrets were the hold-out.
 *
 * Reads fall back to the pre-binding layout (no AAD) so secrets written by older builds keep
 * working, and flag them via [Decoded.needsReencryption] so the caller upgrades them on the
 * fly. New writes are always bound.
 *
 * This object is deliberately Android-free (java.util.Base64, javax.crypto) so the crypto
 * contract is covered by plain JVM unit tests; the Android Keystore boundary lives entirely
 * in [SecretStore].
 */
object SecretStoreCodec {

    /** Decryption result; [needsReencryption] means the blob used the legacy, unbound layout. */
    data class Decoded(val value: String, val needsReencryption: Boolean)

    fun encrypt(name: String, value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(name))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    /**
     * Returns the plaintext, or null for any unreadable payload (wrong name, swappped blob,
     * truncated or tampered data, invalidated key). Never throws on malformed input — a stored
     * secret being unreadable is an expected state that the UI explains, not an exception.
     */
    fun decrypt(name: String, payload: String, key: SecretKey): Decoded? {
        val bytes = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // A GCM blob shorter than IV + tag cannot have been produced by encrypt().
        if (bytes.size <= IV_SIZE + TAG_BYTES) return null
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val body = bytes.copyOfRange(IV_SIZE, bytes.size)
        attempt(key, iv, body, aad(name))?.let { return Decoded(it, needsReencryption = false) }
        attempt(key, iv, body, aad = null)?.let { return Decoded(it, needsReencryption = true) }
        return null
    }

    private fun attempt(key: SecretKey, iv: ByteArray, body: ByteArray, aad: ByteArray?): String? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            aad?.let(cipher::updateAAD)
            cipher.doFinal(body).toString(Charsets.UTF_8)
        }.getOrNull()

    private fun aad(name: String): ByteArray = name.toByteArray(Charsets.UTF_8)

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
}
