package ai.arena.mobet.planner

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSimulatorTest {
    private val snapshot = ScreenSnapshot(
        "com.android.settings", 0,
        listOf(
            InspectedElement("Network & internet", "Button", "text: Network & internet", 1, 90, "0,0–10,10")
        )
    )

    private val flow = Workflow.parse(
        """
        {
          "name": "demo",
          "package": "com.android.settings",
          "steps": [
            { "action": "wait", "text": "Network & internet" },
            { "action": "tap", "text": "Nonexistent thing" },
            { "action": "confirm", "message": "Continue?" },
            { "action": "tap", "text": "Submit payment of ${'$'}20" }
          ]
        }
        """
    )

    @Test
    fun reportGradesGrounding() {
        val report = PlanSimulator.simulate(flow, snapshot)
        assertTrue(report.contains("✔ grounded"))
        assertTrue(report.contains("✖ not on screen"))
    }

    @Test
    fun reportSurfacesRiskAndConfirmations() {
        val report = PlanSimulator.simulate(flow, snapshot)
        assertTrue(report.contains("Confirmation gates: 1"))
        assertTrue(report.contains("CRITICAL") || report.contains("critical"))
    }

    @Test
    fun secretValuesAreMasked() {
        val filled = Workflow.parse(
            """
            {
              "name": "demo",
              "package": "com.example.app",
              "steps": [ { "action": "fill", "viewId": "app:id/pw", "value": "hunter2secret" } ]
            }
            """
        )
        val report = PlanSimulator.simulate(filled, null)
        assertFalse(report.contains("hunter2secret"))
    }

    @Test
    fun mismatchedSnapshotSkipsGrounding() {
        val other = ScreenSnapshot("com.other.app", 0, snapshot.elements)
        val report = PlanSimulator.simulate(flow, other)
        assertTrue(report.contains("grounding skipped"))
    }
}
