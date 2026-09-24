package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First end-to-end execution test for the driver seam.
 *
 * The workflow is parsed from the same JSON representation used by generated
 * plans, then executed by the real WorkflowRunner against a scripted tree. No
 * Android service, Handler, AccessibilityNodeInfo, or emulator is involved.
 */
class WorkflowRunnerE2eTest {
    @Test
    fun generatedPlanExecutesAcrossScriptedScreens() {
        val driver = FakeDriver()
        val scheduler = DeterministicScheduler()
        val logs = mutableListOf<String>()
        var result: Pair<Boolean, String>? = null
        val workflow = Workflow.parse(
            """
            {
              "name": "Complete profile",
              "package": "com.example.demo",
              "steps": [
                {
                  "action": "tap",
                  "text": "Continue",
                  "delayMs": 0,
                  "expect": { "screenChange": true, "textPresent": "Email" }
                },
                {
                  "action": "fill",
                  "viewId": "form/email",
                  "value": "ada@example.com",
                  "delayMs": 0,
                  "expect": { "textPresent": "Email" }
                },
                {
                  "action": "tap",
                  "text": "Save",
                  "delayMs": 0,
                  "expect": { "screenChange": true, "textPresent": "Done" }
                }
              ],
              "policy": {
                "allowedPackages": ["com.example.demo"],
                "allowedActions": ["tap", "fill"],
                "maxActions": 3,
                "maxRuntimeMs": 10000,
                "allowVisualFallbacks": false
              }
            }
            """
        )

        WorkflowRunner(
            driver = driver,
            emitLog = logs::add,
            onFinished = { success, message -> result = success to message },
            launchTarget = true,
            scheduler = scheduler
        ).start(workflow)

        scheduler.runUntilIdle()

        assertEquals(FakeDriver.Screen.DONE, driver.screen)
        assertEquals("ada@example.com", driver.enteredText)
        assertEquals(listOf("Continue", "Save"), driver.clickedLabels)
        assertEquals(listOf("com.example.demo"), driver.launches)
        assertEquals(true, result?.first)
        assertEquals("Completed 3 steps", result?.second)
        assertTrue(logs.any { it.contains("Policy approved") })
    }
}
