package ai.arena.mobet.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On-device verification of [SecretStore] against a real Android Keystore.
 *
 * The JVM suite ([SecretStoreCodecTest]) proves the crypto contract with a software key; what
 * only a device can prove is the AndroidKeyStore boundary itself: key generation under the
 * app's alias, decryptability of written payloads, and `isReadable`'s contract that a damaged
 * or swapped entry reports itself instead of silently pretending to be fine. This is one of
 * the two components the threat model explicitly marks "needs a real device".
 */
class SecretStoreDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SecretStore(context)
    private val preferences = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)

    private val touched = listOf("device_test_alpha", "device_test_beta", "device_test_temp")

    @After
    fun cleanUp() {
        touched.forEach(store::delete)
    }

    @Test
    fun putGetRoundTripsOnARealKeystoreKey() {
        store.put("device_test_alpha", "s3cret — æø · पासवर्ड")
        assertEquals("s3cret — æø · पासवर्ड", store.get("device_test_alpha"))
        assertTrue(store.isReadable("device_test_alpha"))
        assertTrue(store.names().contains("device_test_alpha"))
    }

    @Test
    fun missingAndDeletedSecretsReportUnreadable() {
        assertNull(store.get("device_test_temp"))
        assertFalse(store.isReadable("device_test_temp"))
        store.put("device_test_temp", "v")
        store.delete("device_test_temp")
        assertNull(store.get("device_test_temp"))
        assertFalse(store.isReadable("device_test_temp"))
    }

    @Test
    fun corruptedCiphertextIsUnreadableRatherThanWrong() {
        store.put("device_test_alpha", "pin-0000")
        val raw = preferences.getString("device_test_alpha", null)!!
        // Flip a byte near the end of the Base64 payload so decoding stays valid but GCM fails.
        val mangled = raw.dropLast(2) + if (raw.takeLast(2) == "AA") "AB" else "AA"
        preferences.edit().putString("device_test_alpha", mangled).commit()
        assertNull(store.get("device_test_alpha"))
        assertFalse(store.isReadable("device_test_alpha"))
    }

    @Test
    fun swappedCiphertextsBetweenNamesAreRejectedOnDevice() {
        store.put("device_test_alpha", "alpha-value")
        store.put("device_test_beta", "beta-value")
        val a = preferences.getString("device_test_alpha", null)!!
        val b = preferences.getString("device_test_beta", null)!!
        preferences.edit().putString("device_test_alpha", b).putString("device_test_beta", a).commit()
        // Name-bound AAD: each blob now sits under the wrong name and must fail verification.
        assertNull(store.get("device_test_alpha"))
        assertNull(store.get("device_test_beta"))
        assertFalse(store.isReadable("device_test_alpha"))
        assertFalse(store.isReadable("device_test_beta"))
    }
}
