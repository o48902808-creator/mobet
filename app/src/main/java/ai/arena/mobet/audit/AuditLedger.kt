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
 */
class AuditLedger(context: Context) {
    private val secureStore = EncryptedStateStore(context, "audit_ledger_v2")
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
        save(trimmed)
    }

    @Synchronized
    fun entries(): List<LedgerEntry> = load()

    /** Returns null when the chain is intact, otherwise a description of the first broken link. */
    @Synchronized
    fun verify(): String? {
        val entries = load()
        var previousHash: String? = null
        entries.forEachIndexed { index, entry ->
            if (previousHash != null && entry.previousHash != previousHash) {
                return "Chain broken at entry ${entry.sequence}: previous-hash mismatch"
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
    fun clear() { secureStore.clear(); legacyPreferences.edit().clear().commit() }

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

    private fun save(entries: List<LedgerEntry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("seq", it.sequence).put("ts", it.timestamp)
                    .put("event", it.event).put("hash", it.hash).put("prev", it.previousHash)
            )
        }
        secureStore.write(array.toString())
    }

    private companion object {
        const val GENESIS = "mobet-genesis"
        const val MAX_ENTRIES = 300
    }
}
