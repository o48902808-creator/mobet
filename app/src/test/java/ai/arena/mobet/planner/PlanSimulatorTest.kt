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

    private val controlled = Workflow.parse(
        """
        {
          "name": "control",
          "package": "com.android.settings",
          "policy": { "maxActions": 10 },
          "steps": [
            { "action": "wait", "text": "Network & internet", "label": "start" },
            { "action": "tap", "text": "Nonexistent thing" },
            { "action": "repeatUntil", "goto": "start", "maxIterations": 6,
              "expect": { "textPresent": "Network & internet" } },
            { "action": "branch", "goto": "done",
              "expect": { "textAbsent": "Airplane mode" } },
            { "action": "tryAlternates",
              "options": [ { "text": "Network & internet" }, { "text": "Connections" } ] },
            { "action": "tap", "text": "Network & internet", "label": "done" }
          ]
        }
        """
    )

    @Test
    fun branchRendersBothPathsResolved() {
        val report = PlanSimulator.simulate(controlled, snapshot)
        assertTrue(report.contains("⇄ 4. branch"))
        assertTrue(report.contains("on: “Airplane mode” absent"))
        assertTrue(report.contains("├ satisfied → “done” (step 6)"))
        assertTrue(report.contains("└ fallback  → next step"))
    }

    @Test
    fun repeatUntilRendersLoopSpanAndCap() {
        val report = PlanSimulator.simulate(controlled, snapshot)
        assertTrue(report.contains("↻ 3. repeatuntil"))
        assertTrue(report.contains("until: “Network & internet” present · cap 6×"))
        assertTrue(report.contains("loops → “start” (step 1); steps 1–3 may run up to 6×"))
    }

    @Test
    fun tryAlternatesRendersOptionsWithGradesAndDeadEndNote() {
        val report = PlanSimulator.simulate(controlled, snapshot)
        assertTrue(report.contains("◇ 5. tryalternates"))
        assertTrue(report.contains("1) “Network & internet”  ✔ grounded"))
        assertTrue(report.contains("2) “Connections”  ✖ not on screen"))
        assertTrue(report.contains("dead-end memory may skip recorded-dead options"))
    }

    @Test
    fun worstCasePathIsEstimatedAgainstBothRails() {
        // Loop multiplies steps 1–3 by 6: acting = 6 + 6 + 1 = 13 > budget 10 → warning;
        // hops = 6 (repeat) + 1 (branch) + 1 (alternates) = 8.
        val report = PlanSimulator.simulate(controlled, snapshot)
        assertTrue(report.contains("Control flow: 1 branch · 1 repeat · 1 alternates"))
        assertTrue(report.contains("Worst-case path: ~13 acting steps (budget 10) · ~8 control hops (rail 200)"))
        assertTrue(report.contains("worst-case path outruns the action budget"))
    }

    @Test
    fun groundedTallyCountsOptionsToo() {
        // wait ✔ + two taps (✖, ✔) + options (✔, ✖) → 3 of 5.
        val report = PlanSimulator.simulate(controlled, snapshot)
        assertTrue(report.contains("Grounded selectors: 3/5"))
    }

    @Test
    fun linearReportsDoNotGainControlSections() {
        val report = PlanSimulator.simulate(flow, snapshot)
        assertFalse(report.contains("Control flow:"))
        assertFalse(report.contains("Worst-case path:"))
        assertTrue(report.contains("Grounded selectors: 1/3"))
    }
}
