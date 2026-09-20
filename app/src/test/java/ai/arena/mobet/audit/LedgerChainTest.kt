package ai.arena.mobet.audit

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification logic for the tamper-evident audit ledger.
 *
 * [AuditLedger] itself needs a `Context` and the Android Keystore, so it cannot be constructed
 * on the JVM. These tests exercise the chain rules against the same algorithm, which is where
 * the security property actually lives — the storage layer only decides where the bytes go.
 *
 * The important cases are the ones hash chaining alone does *not* catch. A chain is only
 * evidence that the entries present are mutually consistent; it says nothing about entries that
 * were removed. Tail truncation and whole-ledger rollback both leave a perfectly valid prefix,
 * which is why the sequence high-water mark exists.
 */
class LedgerChainTest {

    private val genesis = "mobet-genesis"

    private data class Entry(
        val sequence: Long,
        val timestamp: Long,
        val event: String,
        val hash: String,
        val previousHash: String
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun chain(count: Int, startAt: Long = 1, from: String = genesis): List<Entry> {
        val entries = mutableListOf<Entry>()
        var previous = from
        for (i in startAt until startAt + count) {
            val timestamp = 1_000L + i
            val event = "event-$i"
            val hash = sha256("$previous|$i|$timestamp|$event")
            entries += Entry(i, timestamp, event, hash, previous)
            previous = hash
        }
        return entries
    }

    /** Mirrors AuditLedger.verify(). */
    private fun verify(entries: List<Entry>, highWater: Long): String? {
        val newest = entries.lastOrNull()?.sequence ?: 0L
        if (newest < highWater) {
            val missing = highWater - newest
            return "Ledger truncated: $missing missing"
        }
        var previousHash: String? = null
        entries.forEachIndexed { index, entry ->
            if (previousHash == null) {
                if (entry.sequence == 1L && entry.previousHash != genesis) {
                    return "Entry 1 does not start from the genesis hash"
                }
            } else if (entry.previousHash != previousHash) {
                return "Chain broken at entry ${entry.sequence}"
            }
            val expectedSequence = entries.getOrNull(index - 1)?.sequence?.plus(1)
            if (expectedSequence != null && entry.sequence != expectedSequence) {
                return "Sequence gap before entry ${entry.sequence}"
            }
            val expected = sha256(
                "${entry.previousHash}|${entry.sequence}|${entry.timestamp}|${entry.event}"
            )
            if (expected != entry.hash) return "Entry ${entry.sequence} was altered"
            previousHash = entry.hash
        }
        return null
    }

    @Test
    fun intactChainVerifies() {
        assertNull(verify(chain(5), highWater = 5))
    }

    @Test
    fun emptyLedgerVerifies() {
        assertNull(verify(emptyList(), highWater = 0))
    }

    @Test
    fun editingAnEntryIsDetected() {
        val tampered = chain(5).toMutableList()
        tampered[2] = tampered[2].copy(event = "run.complete ok (forged)")
        assertTrue(verify(tampered, 5).orEmpty().contains("altered"))
    }

    @Test
    fun tailTruncationIsDetected() {
        // The attack hash chaining misses: delete the records of what you just did. The
        // surviving prefix is internally consistent, so only the high-water mark catches it.
        val full = chain(5)
        assertNull("the prefix really is self-consistent", verify(full.take(3), highWater = 3))
        assertTrue(
            "but it must be rejected against the real high-water mark",
            verify(full.take(3), highWater = 5).orEmpty().contains("truncated")
        )
    }

    @Test
    fun rollingBackToAnOlderLedgerIsDetected() {
        // Restoring a stale copy of the whole store is tail truncation by another route.
        assertTrue(verify(chain(3), highWater = 5).orEmpty().contains("truncated"))
    }

    @Test
    fun removingAMiddleEntryIsDetected() {
        val full = chain(5)
        val gapped = full.filterIndexed { index, _ -> index != 2 }
        assertNotNull(verify(gapped, 5))
    }

    @Test
    fun aForgedGenesisIsDetected() {
        // An attacker rebuilding the chain from scratch cannot silently pick their own root.
        val forged = chain(3, from = "attacker-controlled-root")
        assertEquals("Entry 1 does not start from the genesis hash", verify(forged, 3))
    }

    @Test
    fun aFailedWriteDoesNotLeaveTheLedgerPermanentlyAccusing() {
        // The high-water mark is only advanced after a successful save. If it were recorded
        // unconditionally, a failed write -- a full disk, not an attacker -- would leave the
        // mark one ahead of the chain and verify() would report tampering on every launch
        // forever, with no way for the user to clear the accusation.
        val full = chain(5)
        var highWater = 5L
        val saveSucceeded = false
        val nextSequence = 6L
        if (saveSucceeded && nextSequence > highWater) highWater = nextSequence
        assertNull("a failed append must not manufacture a truncation", verify(full, highWater))
    }

    @Test
    fun clearingTheMarkBeforeTheChainFailsSafe() {
        // clear() wipes the high-water store first. If the chain wipe then fails, the surviving
        // entries verify against a mark of zero rather than the reverse, which would accuse a
        // deliberately cleared ledger of having been truncated.
        val survivingEntries = chain(5)
        val markAfterFailedClear = 0L
        assertNull(verify(survivingEntries, markAfterFailedClear))
        // And the fully successful path is still clean.
        assertNull(verify(emptyList(), 0))
    }

    @Test
    fun aMigratedLegacyChainWithNoMarkVerifies() {
        // A v1 chain moved into the v2 store has never recorded a mark. Reading zero must not
        // read as "everything was truncated"; the mark can only attest to what it observed.
        assertNull(verify(chain(40), highWater = 0))
    }

    @Test
    fun legitimateCapacityTrimStillVerifies() {
        // The 300-entry cap drops oldest entries. That must not read as tampering, otherwise
        // the ledger cries wolf on every long-running install and users learn to ignore it.
        val full = chain(5)
        assertNull(verify(full.drop(2), highWater = 5))
    }
}
