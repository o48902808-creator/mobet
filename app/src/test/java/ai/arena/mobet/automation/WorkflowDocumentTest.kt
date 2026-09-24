package ai.arena.mobet.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDocumentTest {

    private val document = """
        {
          "name": "Demo",
          "package": "com.example.app",
          "policy": {
            "allowedPackages": ["com.example.app", "com.example.helper"],
            "allowedActions": ["tap", "confirm"],
            "maxActions": 10,
            "maxRuntimeMs": 60000
          },
          "steps": [{"action": "tap", "text": "Continue"}]
        }
    """.trimIndent()

    // ── Summary ──────────────────────────────────────────────────────────────

    @Test
    fun blankDocumentHasNoSummary() {
        assertNull(WorkflowDocument.summarize("   "))
    }

    @Test
    fun validDocumentIsSummarized() {
        val summary = WorkflowDocument.summarize(document)!!
        assertEquals("com.example.app", summary.packageName)
        assertEquals(1, summary.stepCount)
        assertEquals(10, summary.maxActions)
        assertEquals(60L, summary.runtimeSeconds)
        assertFalse(summary.visualFallbacks)
        assertTrue(summary.isValid)
    }

    @Test
    fun invalidJsonReportsTheParseErrorAndNothingElse() {
        val summary = WorkflowDocument.summarize("{ not json")!!
        assertNotNull(summary.parseError)
        assertFalse(summary.isValid)
        assertEquals(0, summary.stepCount)
    }

    @Test
    fun policyViolationsSurfaceInTheSummary() {
        val offending = document.replace("\"allowedActions\": [\"tap\", \"confirm\"]", "\"allowedActions\": [\"wait\"]")
        val summary = WorkflowDocument.summarize(offending)!!
        assertNull(summary.parseError)
        assertFalse(summary.isValid)
        assertTrue(summary.violations.isNotEmpty())
    }

    // ── Retargeting ──────────────────────────────────────────────────────────

    @Test
    fun retargetingReplacesThePreviousTargetButKeepsOtherAllowances() {
        val result = JSONObject(WorkflowDocument.retarget(document, "com.other.app"))
        assertEquals("com.other.app", result.getString("package"))
        val allowed = result.getJSONObject("policy").getJSONArray("allowedPackages")
        val packages = (0 until allowed.length()).map(allowed::getString).toSet()
        assertEquals(setOf("com.example.helper", "com.other.app"), packages)
    }

    @Test
    fun retargetingADocumentWithoutAPolicyCreatesOne() {
        val minimal = """{"steps":[{"action":"back"}]}"""
        val result = JSONObject(WorkflowDocument.retarget(minimal, "com.example.app"))
        val allowed = result.getJSONObject("policy").getJSONArray("allowedPackages")
        assertEquals("com.example.app", allowed.getString(0))
    }

    @Test
    fun retargetingRejectsABlankPackage() {
        runCatching { WorkflowDocument.retarget(document, " ") }
            .onSuccess { throw AssertionError("blank target must be rejected") }
    }

    // ── Appending recorded steps ─────────────────────────────────────────────

    @Test
    fun appendingWidensTheAllowlistWithoutRemovingAnything() {
        val steps = JSONArray().put(JSONObject().put("action", "tap").put("text", "Save"))
        val result = JSONObject(WorkflowDocument.appendSteps(document, steps, "com.recorded.app"))
        assertEquals(2, result.getJSONArray("steps").length())
        val allowed = result.getJSONObject("policy").getJSONArray("allowedPackages")
        val packages = (0 until allowed.length()).map(allowed::getString).toSet()
        assertEquals(
            setOf("com.example.app", "com.example.helper", "com.recorded.app"),
            packages
        )
        // An already-declared target is never silently changed by an import.
        assertEquals("com.example.app", result.getString("package"))
    }

    @Test
    fun appendingAdoptsTheTargetOnlyWhenTheDocumentHasNone() {
        val minimal = """{"steps":[{"action":"back"}]}"""
        val steps = JSONArray().put(JSONObject().put("action", "tap").put("text", "Save"))
        val result = JSONObject(WorkflowDocument.appendSteps(minimal, steps, "com.recorded.app"))
        assertEquals("com.recorded.app", result.getString("package"))
    }

    @Test
    fun appendingWithoutATargetLeavesThePolicyUntouched() {
        val steps = JSONArray().put(JSONObject().put("action", "back"))
        val result = JSONObject(WorkflowDocument.appendSteps(document, steps, null))
        val allowed = result.getJSONObject("policy").getJSONArray("allowedPackages")
        assertEquals(2, allowed.length())
    }
}
