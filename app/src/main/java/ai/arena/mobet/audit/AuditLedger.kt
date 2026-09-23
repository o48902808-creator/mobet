package ai.arena.mobet.audit

import ai.arena.mobet.agent.ScreenFingerprint
import ai.arena.mobet.security.EncryptedStateStore
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LedgerEntry(
    val sequence: Long,
    val timestamp: Long,
    val event: String,
    val hash: String,
    val previousHash: String
)

/**
 * Tamper-evident, hash-chained execution ledger.
 *
 * Every runner event is appended as `hash = SHA-256(previousHash | sequence | timestamp | event)`,
 * forming a local blockchain-style chain. [verify] recomputes the chain and reports the first
 * broken link, so any after-the-fact edit of the history is detectable. The ledger is bounded,
 * stays entirely on-device, and stores runner status text only — never screen content or secrets.
 *
 * That last guarantee is enforced upstream: [ai.arena.mobet.automation.WorkflowRunner] masks
 * every resolved `{{secret:…}}` value before a line is emitted, because failure messages quote
 * the selector that failed and a selector may legitimately hold a secret.
 */
class AuditLedger(context: Context) {
    private val secureStore = EncryptedStateStore(context, "audit_ledger_v2")
    private val highWaterStore = EncryptedStateStore(context, "audit_ledger_high_water")
    private val deviceRunStore = EncryptedStateStore(context, "audit_ledger_device_run")
    private val legacyPreferences = context.getSharedPreferences("audit_ledger", Context.MODE_PRIVATE)

    init {
        if (secureStore.read() == null) legacyPreferences.getString("chain", null)?.let { legacy ->
            if (secureStore.write(legacy)) legacyPreferences.edit().clear().commit()
        }
    }

    @Synchronized
    fun append(event: String) {
        val entries = load()
        val previousHash = entries.lastOrNull()?.hash ?: GENESIS
        val sequence = (entries.lastOrNull()?.sequence ?: 0L) + 1
        val timestamp = System.currentTimeMillis()
        val hash = ScreenFingerprint.sha256("$previousHash|$sequence|$timestamp|$event")
        val trimmed = (entries + LedgerEntry(sequence, timestamp, event, hash, previousHash))
            .takeLast(MAX_ENTRIES)
        // Only advance the high-water mark once the entry is actually on disk. Recording it for
        // a write that failed would leave the mark ahead of the chain forever, and verify()
        // would report tampering on every launch for what was really a full disk. Under-
        // recording is the safe direction: it can only miss a truncation, never invent one.
        val saved = save(trimmed)
        if (saved) recordHighWater(sequence)
        return saved
    }

    @Synchronized
    fun entries(): List<LedgerEntry> = load()

    /**
     * Opaque installation-local identifier for correlating exports without exposing a device ID.
     * It deliberately survives ledger clearing: it identifies this installation, not one chain.
     */
    @Synchronized
    fun deviceRunId(): String {
        deviceRunStore.read()?.takeIf { DEVICE_RUN_PATTERN.matches(it) }?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        return if (deviceRunStore.write(generated)) generated else "ephemeral-$generated"
    }

    /** Returns null when the chain is intact, otherwise a description of the first broken link. */
    @Synchronized
    fun verify(): String? {
        val entries = load()

        // Tail truncation check. Hash chaining alone cannot detect the removal of the most
        // recent entries: the surviving prefix is still internally consistent, so an attacker
        // who can edit the store could delete exactly the records of what they just did and
        // still see "chain verified". The highest sequence ever written is therefore kept
        // separately, and a ledger that has gone backwards is reported.
        val highWater = highWaterMark()
        val newest = entries.lastOrNull()?.sequence ?: 0L
        if (newest < highWater) {
            val missing = highWater - newest
            return "Ledger truncated: $missing entr${if (missing == 1L) "y is" else "ies are"} " +
                "missing from the end (highest recorded sequence was $highWater, newest is $newest)"
        }

        var previousHash: String? = null
        entries.forEachIndexed { index, entry ->
            if (previousHash == null) {
                // The first surviving entry must either start the chain at GENESIS or be the
                // result of the MAX_ENTRIES trim, which only ever drops from the front.
                if (entry.sequence == 1L && entry.previousHash != GENESIS) {
                    return "Entry 1 does not start from the genesis hash"
                }
            } else if (entry.previousHash != previousHash) {
                return "Chain broken at entry ${entry.sequence}: previous-hash mismatch"
            }
            // Sequence numbers must be strictly consecutive; a gap means an entry was removed
            // from the middle and the surrounding links were re-stitched.
            val expectedSequence = entries.getOrNull(index - 1)?.sequence?.plus(1)
            if (expectedSequence != null && entry.sequence != expectedSequence) {
                return "Sequence gap before entry ${entry.sequence}: expected $expectedSequence"
            }
            val expected = ScreenFingerprint.sha256(
                "${entry.previousHash}|${entry.sequence}|${entry.timestamp}|${entry.event}"
            )
            if (expected != entry.hash) {
                return "Entry ${entry.sequence} was altered (hash mismatch at position ${index + 1})"
            }
            previousHash = entry.hash
        }
        return null
    }

    @Synchronized
    fun clear() {
        // Order matters. Clearing the chain first and then failing to clear the mark would leave
        // a high-water mark above an empty chain, so verify() would report a truncation attack
        // on a ledger the user deliberately cleared. Clearing the mark first fails safe: if the
        // chain clear then fails, the surviving entries simply verify against a mark of zero.
        highWaterStore.clear()
        secureStore.clear()
        legacyPreferences.edit().clear().commit()
    }

    /**
     * Highest sequence number ever appended, kept in its own authenticated store.
     *
     * Deliberately separate from the chain so that replacing the chain blob does not also
     * replace the evidence of how long it used to be.
     */
    private fun highWaterMark(): Long = highWaterStore.read()?.toLongOrNull() ?: 0L

    private fun recordHighWater(sequence: Long) {
        if (sequence > highWaterMark()) highWaterStore.write(sequence.toString())
    }

    private fun load(): List<LedgerEntry> = try {
        val array = JSONArray(secureStore.read() ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    LedgerEntry(
                        sequence = item.getLong("seq"),
                        timestamp = item.getLong("ts"),
                        event = item.getString("event"),
                        hash = item.getString("hash"),
                        previousHash = item.getString("prev")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(entries: List<LedgerEntry>): Boolean {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("seq", it.sequence).put("ts", it.timestamp)
                    .put("event", it.event).put("hash", it.hash).put("prev", it.previousHash)
            )
        }
        return secureStore.write(array.toString())
    }

    private companion object {
        const val GENESIS = "mobet-genesis"
        const val MAX_ENTRIES = 300
        val DEVICE_RUN_PATTERN = Regex("(?:ephemeral-)?[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
