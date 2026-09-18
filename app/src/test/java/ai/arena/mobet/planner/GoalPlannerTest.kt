package ai.arena.mobet.planner

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalPlannerTest {
    private fun element(label: String, selector: String, role: String = "Button") =
        InspectedElement(label, role, selector, 1, 90, "0,0–10,10")

    private val settingsSnapshot = ScreenSnapshot(
        "com.android.settings", 0,
        listOf(
            element("Network & internet", "text: Network & internet"),
            element("Connected devices", "text: Connected devices"),
            element("Search settings", "viewId: com.android.settings:id/search", role = "EditText")
        )
    )

    @Test
    fun groundedGoalCompilesAndValidates() {
        val result = GoalPlanner.generate(
            "tap \"Network & internet\" then wait \"Network & internet\"",
            settingsSnapshot
        )
        assertTrue(result.isSuccess)
        val workflow = Workflow.parse(result.getOrThrow())
        assertEquals("com.android.settings", workflow.packageName)
        assertEquals("tap", workflow.steps.first().action)
    }

    @Test
    fun hallucinatedTargetIsRejected()  {
        val result = GoalPlanner.generate("tap \"Teleport button\"", settingsSnapshot)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not grounded"))
    }

    @Test
    fun paraphrasedTargetStillGrounds() {
        val result = GoalPlanner.generate("tap \"Network and internet\"", settingsSnapshot)
        assertTrue(result.isSuccess)
    }

    @Test
    fun consequentialClauseGetsAutomaticConfirmation() {
        val snapshot = ScreenSnapshot(
            "com.example.shop", 0,
            listOf(element("Submit order", "text: Submit order"))
        )
        val result = GoalPlanner.generate("tap \"Submit order\"", snapshot)
        assertTrue(result.isSuccess)
        val root = JSONObject(result.getOrThrow())
        val steps = root.getJSONArray("steps")
        assertEquals("confirm", steps.getJSONObject(0).getString("action"))
        assertEquals("tap", steps.getJSONObject(1).getString("action"))
    }

    @Test
    fun fillGroundsAgainstEditableElementsOnly() {
        val result = GoalPlanner.generate("fill \"Search settings\" with \"wifi\"", settingsSnapshot)
        assertTrue(result.isSuccess)
        val steps = JSONObject(result.getOrThrow()).getJSONArray("steps")
        var found = false
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.getString("action") == "fill") {
                found = true
                // Must target the EditText's viewId — never a button label.
                assertEquals("com.android.settings:id/search", step.getString("viewId"))
                assertEquals("wifi", step.getString("value"))
            }
        }
        assertTrue(found)
    }

    @Test
    fun backClauseIsSupported() {
        val result = GoalPlanner.generate(
            "tap \"Network & internet\" then go back",
            settingsSnapshot
        )
        assertTrue(result.isSuccess)
        val steps = JSONObject(result.getOrThrow()).getJSONArray("steps")
        assertEquals("back", steps.getJSONObject(steps.length() - 1).getString("action"))
    }

    @Test
    fun emptyGoalIsRejected() {
        assertTrue(GoalPlanner.generate("   ", settingsSnapshot).isFailure)
    }
}
