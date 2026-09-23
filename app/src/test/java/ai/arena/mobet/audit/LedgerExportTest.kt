package ai.arena.mobet.audit

import ai.arena.mobet.agent.ScreenFingerprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerExportTest {
    private val build = LedgerBuildIdentity(
        version = "0.8.0",
        versionCode = 9,
        apkSha256 = "a".repeat(64),
        manifestSha256 = "b".repeat(64),
        commit = "abc123",
        signing = "debug",
        provenanceVerified = true,
        policyVersion = "deterministic-policy.v1",
        ledgerSchemaVersion = "mobet.ledger.v1/hash-chain.v2"
    )

    private fun entries(events: List<String>): List<LedgerEntry> {
        var previous = "mobet-genesis"
        return events.mapIndexed { index, event ->
            val seq = index + 1L
            val timestamp = 1_000L + index
            val hash = ScreenFingerprint.sha256("$previous|$seq|$timestamp|$event")
            LedgerEntry(seq, timestamp, event, hash, previous).also { previous = hash }
        }
    }

    @Test fun exportContainsBuildPrivacyAndBothChainHeads() {
        val source = entries(listOf("run started", "run complete"))
        val root = JSONObject(LedgerExport.json(source, "run-12345678", build, true, 2_000L))
        assertEquals(LedgerExport.SCHEMA, root.getString("schema"))
        assertEquals("a".repeat(64), root.getJSONObject("build").getString("apkSha256"))
        assertFalse(root.getJSONObject("privacy").getBoolean("screenshotsIncluded"))
        assertFalse(root.getJSONObject("privacy").getBoolean("secretsIncluded"))
        assertEquals(source.last().hash, root.getJSONObject("chain").getString("sourceHead"))
        assertTrue(root.getJSONObject("chain").getBoolean("verified"))
    }

    @Test fun redactedEventsAreRechainedOverExportedContent() {
        val source = entries(listOf("authorization=super-secret", "sent to person@example.com"))
        val root = JSONObject(LedgerExport.json(source, "run-12345678", build, true, 2_000L))
        val exported = root.getJSONArray("entries")
        val chain = root.getJSONObject("chain")
        assertEquals(2, chain.getInt("redactedEntries"))
        assertEquals("authorization=[REDACTED]", exported.getJSONObject(0).getString("event"))
        assertEquals("sent to [REDACTED_EMAIL]", exported.getJSONObject(1).getString("event"))

        var previous = chain.getString("genesis")
        for (index in 0 until exported.length()) {
            val entry = exported.getJSONObject(index)
            assertEquals(previous, entry.getString("prev"))
            val expected = ScreenFingerprint.sha256(
                "$previous|${entry.getLong("seq")}|${entry.getLong("ts")}|${entry.getString("event")}"
            )
            assertEquals(expected, entry.getString("hash"))
            previous = expected
        }
        assertEquals(previous, chain.getString("head"))
        assertEquals(source.last().hash, chain.getString("sourceHead"))
    }

    @Test fun commonSecretFormsAreRedacted() {
        val source = "token=abc Bearer eyJ.secret {{secret:bank}} 4111 1111 1111 1111"
        val redacted = LedgerExport.redact(source)
        assertFalse(redacted.contains("token=abc"))
        assertFalse(redacted.contains("eyJ.secret"))
        assertFalse(redacted.contains("{{secret:"))
        assertFalse(redacted.contains("4111"))
    }

    @Test fun emptyLedgerStillHasVerifiableExportGenesis() {
        val root = JSONObject(LedgerExport.json(emptyList(), "run-12345678", build, true, 2_000L))
        val chain = root.getJSONObject("chain")
        assertEquals(chain.getString("genesis"), chain.getString("head"))
        assertEquals("", chain.getString("sourceHead"))
    }
}
