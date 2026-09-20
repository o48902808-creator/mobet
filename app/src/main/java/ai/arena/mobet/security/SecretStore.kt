package ai.arena.mobet.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM encrypted secret values. The non-exportable key remains in Android Keystore. */
class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)

    fun names(): List<String> = preferences.all.keys.sorted()

    /**
     * Whether a stored secret can still be decrypted.
     *
     * The AES key lives in the Android Keystore and is device-bound. It can be invalidated
     * outside the app's control -- a lock-screen change on some OEMs, a restore to new hardware,
     * keystore corruption -- which leaves the ciphertext permanently unreadable while the
     * plaintext preference *key* survives. Without this check the secret still appears in the
     * list as if it were fine, and every run that references it fails with a message that points
     * at the run rather than at the secret. Re-entering the value is the only fix, so the UI has
     * to be able to say so.
     */
    fun isReadable(name: String): Boolean = get(name) != null

    fun put(name: String, value: String) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Invalid secret name" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        preferences.edit().putString(name, payload).apply()
    }

    fun get(name: String): String? {
        val payload = preferences.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)))
            cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun delete(name: String) = preferences.edit().remove(name).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "mobet.workflow.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
