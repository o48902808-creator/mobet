package ai.arena.mobet.automation

import ai.arena.mobet.agent.ScreenFingerprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import/export rules for the workflow library.
 *
 * [WorkflowTransfer.commit] and [WorkflowTransfer.readUri] need a `Context`, so the logic they
 * depend on is reproduced here. The two behaviours worth pinning are the ones that were wrong:
 * name de-duplication has to consider names staged earlier in the same batch, and the size cap
 * on an imported file has to be enforced while reading rather than afterwards.
 */
class WorkflowTransferLogicTest {

    // ── Name de-duplication ──────────────────────────────────────────────────

    /**
     * Mirrors commit()'s naming. `taken` starts as the library's existing keys and grows as
     * the batch is staged, because a SharedPreferences editor's writes are invisible to
     * contains() until apply().
     */
    private fun commit(
        library: MutableMap<String, String>,
        incoming: List<Pair<String, String>>
    ): Int {
        val taken = library.keys.toMutableSet()
        val staged = mutableMapOf<String, String>()
        var written = 0
        incoming.forEach { (name, source) ->
            var candidate = name
            var suffix = 2
            while (!taken.add(candidate)) {
                candidate = "$name ($suffix)"
                suffix++
            }
            staged[candidate] = source
            written++
        }
        library.putAll(staged)
        return written
    }

    @Test
    fun duplicateNamesWithinOneBundleAreAllKept() {
        // The regression: every entry resolved to the same free name, so the last write won and
        // the rest vanished while the UI still reported them as imported.
        val library = mutableMapOf<String, String>()
        val written = commit(
            library,
            listOf("Checkout" to "A", "Checkout" to "B", "Checkout" to "C")
        )
        assertEquals(3, written)
        assertEquals("the reported count must match what is on disk", 3, library.size)
        assertEquals(setOf("Checkout", "Checkout (2)", "Checkout (3)"), library.keys)
        assertEquals(setOf("A", "B", "C"), library.values.toSet())
    }

    @Test
    fun importNeverOverwritesAnExistingWorkflow() {
        val library = mutableMapOf("Checkout" to "original")
        commit(library, listOf("Checkout" to "imported"))
        assertEquals("original", library["Checkout"])
        assertEquals("imported", library["Checkout (2)"])
    }

    @Test
    fun collisionsWithGeneratedNamesAlsoResolve() {
        // "Checkout (2)" is already taken, so the import must skip past it rather than clash.
        val library = mutableMapOf("Checkout" to "a", "Checkout (2)" to "b")
        commit(library, listOf("Checkout" to "c"))
        assertEquals(3, library.size)
        assertEquals("c", library["Checkout (3)"])
    }

    @Test
    fun distinctNamesAreUnchanged() {
        val library = mutableMapOf<String, String>()
        commit(library, listOf("One" to "1", "Two" to "2"))
        assertEquals(setOf("One", "Two"), library.keys)
    }

    // ── Size cap ─────────────────────────────────────────────────────────────

    private val maxBytes = 2 * 1024 * 1024

    /** Mirrors readUri()'s streaming read: the cap is checked before each chunk is retained. */
    private fun readBounded(source: ByteArray, chunkSize: Int = 16 * 1024): Result<Int> =
        runCatching {
            var held = 0
            var offset = 0
            while (offset < source.size) {
                val read = minOf(chunkSize, source.size - offset)
                require(held + read <= maxBytes) { "File is larger than ${maxBytes / 1024} KB" }
                held += read
                offset += read
            }
            held
        }

    @Test
    fun aFileWithinTheCapIsRead() {
        val result = readBounded(ByteArray(64 * 1024))
        assertTrue(result.isSuccess)
        assertEquals(64 * 1024, result.getOrNull())
    }

    @Test
    fun anOversizedFileIsRejected() {
        val result = readBounded(ByteArray(maxBytes + 1))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("larger than"))
    }

    @Test
    fun theCapIsEnforcedBeforeTheWholeFileIsHeld() {
        // The point of streaming: a 50 MB pick must fail without ever holding 50 MB. Track the
        // high-water mark of retained bytes and assert it never materially exceeds the cap.
        var peak = 0
        val huge = 50 * 1024 * 1024
        val chunk = 16 * 1024
        var held = 0
        var offset = 0
        var rejected = false
        while (offset < huge) {
            val read = minOf(chunk, huge - offset)
            if (held + read > maxBytes) { rejected = true; break }
            held += read
            peak = maxOf(peak, held)
            offset += read
        }
        assertTrue("the read must be refused", rejected)
        assertTrue("never buffered more than the cap, peak was $peak", peak <= maxBytes)
    }

    // ── Export ───────────────────────────────────────────────────────────────

    @Test
    fun exportFileNamesAreSanitised() {
        // Path traversal and separators must not survive into a shared file name.
        val hostile = WorkflowTransfer.sanitizeFileName("../../etc/passwd")
        assertFalse(hostile.contains("/"))
        assertFalse(hostile.contains(".."))
        assertTrue(hostile.endsWith(".json"))
    }

    @Test
    fun blankFileNameFallsBackToADefault() {
        assertEquals("mobet-workflows.json", WorkflowTransfer.sanitizeFileName("   "))
    }

    @Test
    fun anExistingJsonSuffixIsNotDoubled() {
        assertEquals("flows.json", WorkflowTransfer.sanitizeFileName("flows.json"))
    }

    // ── Bundle parsing ───────────────────────────────────────────────────────

    @Test
    fun aBundleFromTheFutureIsRefused() {
        val source = """{"format":"mobet.workflow-bundle","version":99,"workflows":[]}"""
        val result = WorkflowTransfer.parseBundle(source)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("newer"))
    }

    @Test
    fun garbageIsRefusedRatherThanPartiallyImported() {
        assertTrue(WorkflowTransfer.parseBundle("not json at all").isFailure)
    }

    @Test
    fun anEmptyBundleIsRefused() {
        val source = """{"format":"mobet.workflow-bundle","version":1,"workflows":[]}"""
        assertTrue(WorkflowTransfer.parseBundle(source).isFailure)
    }

    @Test
    fun versionTwoContentHashIsRequiredAndVerified() {
        val workflow = JSONObject("""{"package":"com.example.app","steps":[{"action":"back"}]}""")
        val hash = ScreenFingerprint.sha256(workflow.toString())
        val source = JSONObject()
            .put("format", WorkflowTransfer.FORMAT)
            .put("version", 2)
            .put("workflows", org.json.JSONArray().put(
                JSONObject().put("name", "Verified").put("contentHash", hash).put("workflow", workflow)
            )).toString()
        val result = WorkflowTransfer.parseBundle(source).getOrThrow()
        assertEquals(1, result.validCount)
        assertTrue(result.workflows.single().contentHashVerified)
    }

    @Test
    fun tamperedVersionTwoWorkflowStaysQuarantined() {
        val workflow = JSONObject("""{"package":"com.example.app","steps":[{"action":"back"}]}""")
        val source = JSONObject()
            .put("format", WorkflowTransfer.FORMAT)
            .put("version", 2)
            .put("workflows", org.json.JSONArray().put(
                JSONObject().put("name", "Tampered").put("contentHash", "0".repeat(64)).put("workflow", workflow)
            )).toString()
        val result = WorkflowTransfer.parseBundle(source).getOrThrow()
        assertEquals(0, result.validCount)
        assertTrue(result.workflows.single().detail.contains("quarantined"))
    }

    @Test
    fun aMalformedEntryIsReportedRatherThanDiscardingTheBundle() {
        // One bad workflow must not cost the user the good ones; it is surfaced as invalid.
        val source = """
            {"format":"mobet.workflow-bundle","version":1,"workflows":[
              {"name":"Good","workflow":{"steps":[{"action":"back"}]}},
              {"name":"Bad","workflow":{"nosteps":true}}
            ]}
        """.trimIndent()
        val result = WorkflowTransfer.parseBundle(source).getOrThrow()
        assertEquals(2, result.workflows.size)
        assertEquals(1, result.validCount)
        assertEquals("Good", result.workflows.first { it.valid }.name)
        assertFalse(result.workflows.first { it.name == "Bad" }.valid)
    }
}
