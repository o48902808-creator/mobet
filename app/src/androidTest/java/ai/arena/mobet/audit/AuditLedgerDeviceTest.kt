package ai.arena.mobet.audit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * On-device chain persistence: the ledger logic is JVM-covered, but the encrypted storage it
 * actually writes through on a phone (AES-GCM, Keystore keys, atomic commits) is only real
 * here. Guard the flow a user relies on: append → verify → clear → verify is quiet again.
 */
class AuditLedgerDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ledger = AuditLedger(context)

    @After
    fun cleanUp() = ledger.clear()

    @Test
    fun appendedChainVerifiesOnDevice() {
        ledger.clear()
        ledger.append("device-test: started run")
        ledger.append("device-test: completed 2 steps")
        assertNull(ledger.verify())
        val entries = ledger.entries()
        assertEquals(2, entries.size)
        assertEquals(1L, entries[0].sequence)
        assertEquals(2L, entries[1].sequence)
    }

    @Test
    fun clearLeavesNoTruncationAlarm() {
        ledger.clear()
        ledger.append("device-test: before clear")
        ledger.clear()
        // Clearing the ledger must not trip the high-water tamper detection on next read.
        assertNull(ledger.verify())
    }
}
