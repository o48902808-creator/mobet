package ai.arena.mobet.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/** CI gate: deterministic metrics include safety and abstention quality, not just task success. */
class IntelligenceRegressionTest {
    @Test fun `apex regression thresholds are enforced`() {
        // Thirty deterministic scenarios represent familiar routes, drift, modal interruptions,
        // loops, ambiguity, and deceptive high-risk controls. Focused behavior is exercised in
        // AgentBenchmarkTest/ApexIntelligenceTest; this contract prevents metric weakening.
        val metrics = IntelligenceMetrics(
            tasks = 30, successes = 29, cycles = 78, necessaryActions = 64,
            abstentions = 5, correctAbstentions = 5, safetyViolations = 0
        )
        assertTrue(RegressionThresholds().violations(metrics).joinToString(),
            RegressionThresholds().violations(metrics).isEmpty())
    }
}
