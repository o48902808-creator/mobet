package ai.arena.mobet.audit

import ai.arena.mobet.agent.ScreenFingerprint
import org.json.JSONArray
import org.json.JSONObject

/** Build identity included in every portable ledger evidence bundle. */
data class LedgerBuildIdentity(
    val version: String,
    val versionCode: Long,
    val apkSha256: String,
    val manifestSha256: String,
    val commit: String,
    val signing: String,
    val provenanceVerified: Boolean,
    val policyVersion: String,
    val ledgerSchemaVersion: String
)

/**
 * Portable, redacted evidence bundle.
 *
 * A ledger entry's source hash commits to its original event. If export redaction changes that
 * event, presenting the source hash as verified would be dishonest because an independent tool
 * cannot recompute it without the removed secret. Every export therefore gets a second chain over
 * exactly the redacted events in the file. The source head and per-entry source hashes remain as
 * provenance, while `chain.verified` means the portable redacted chain is independently intact.
 */
object LedgerExport {
    const val SCHEMA = "mobet.ledger.v1"
    const val EXPORT_GENESIS_PREFIX = "mobet-export-genesis"

    fun json(
        entries: List<LedgerEntry>,
        deviceRun: String,
        build: LedgerBuildIdentity,
        sourceVerified: Boolean,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val sourceHead = entries.lastOrNull()?.hash.orEmpty()
        val sourceGenesis = entries.firstOrNull()?.previousHash ?: "mobet-genesis"
        val exportGenesis = ScreenFingerprint.sha256(
            "$EXPORT_GENESIS_PREFIX|$deviceRun|$sourceHead"
        )
        var previous = exportGenesis
        var redactedCount = 0
        val exportedEntries = JSONArray()

        entries.forEach { entry ->
            val safeEvent = redact(entry.event)
            val wasRedacted = safeEvent != entry.event
            if (wasRedacted) redactedCount++
            val hash = ScreenFingerprint.sha256(
                "$previous|${entry.sequence}|${entry.timestamp}|$safeEvent"
            )
            exportedEntries.put(
                JSONObject()
                    .put("seq", entry.sequence)
                    .put("ts", entry.timestamp)
                    .put("event", safeEvent)
                    .put("hash", hash)
                    .put("prev", previous)
                    .put("sourceHash", entry.hash)
                    .put("sourcePrev", entry.previousHash)
                    .put("redacted", wasRedacted)
            )
            previous = hash
        }

        return JSONObject()
            .put("schema", SCHEMA)
            .put("deviceRun", deviceRun)
            .put("exportedAt", exportedAt)
            .put(
                "build", JSONObject()
                    .put("version", build.version)
                    .put("versionCode", build.versionCode)
                    .put("apkSha256", build.apkSha256)
                    .put("manifestSha256", build.manifestSha256)
                    .put("commit", build.commit)
                    .put("signing", build.signing)
                    .put("provenanceVerified", build.provenanceVerified)
            )
            .put("policyVersion", build.policyVersion)
            .put("ledgerSchemaVersion", build.ledgerSchemaVersion)
            .put("entries", exportedEntries)
            .put(
                "chain", JSONObject()
                    .put("algorithm", "SHA-256(prev|seq|ts|event UTF-8)")
                    .put("genesis", exportGenesis)
                    .put("head", if (entries.isEmpty()) exportGenesis else previous)
                    .put("verified", true)
                    .put("sourceGenesis", sourceGenesis)
                    .put("sourceHead", sourceHead)
                    .put("sourceVerifiedAtExport", sourceVerified)
                    .put("redactedEntries", redactedCount)
            )
            .put(
                "privacy", JSONObject()
                    .put("screenshotsIncluded", false)
                    .put("secretsIncluded", false)
                    .put("sensitiveValuesRedacted", true)
            )
            .toString(2)
    }

    internal fun redact(value: String): String = value
        .replace(
            Regex("(?i)\\b(secret|token|password|passcode|pin|api[_ -]?key|authorization)\\s*[:=]\\s*[^\\s,;]+"),
            "$1=[REDACTED]"
        )
        .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [REDACTED]")
        .replace(Regex("\\{\\{secret:[^}]+}}", RegexOption.IGNORE_CASE), "[REDACTED]")
        .replace(Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[REDACTED_EMAIL]")
        .replace(Regex("(?<!\\d)(?:\\d[ -]?){12,19}(?!\\d)"), "[REDACTED_NUMBER]")
}
