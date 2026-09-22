package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Locks the deterministic analogue of State-of-Thought evidence organization
 * (docs/STATE_OF_THOUGHT.md): the regime — never the evidence list alone — decides how much
 * noisy support counts, and the provable no-op case is the healthy regime.
 *
 * Note on the mechanic under test: [BeliefReasoner] normalises scores into probabilities, so
 * source weights only bind through *competing* hypotheses. That competition is exactly where
 * regime conditioning pays: stale OCR loses to mild grounded counter-evidence precisely when
 * the run is stuck.
 */
class StateOfThoughtPolicyTest {

    private fun policy(regime: ReasoningRegime) = StateOfThoughtPolicy.evidencePolicy(regime)

    @Test
    fun `healthy regime is exactly the default policy`() {
        assertEquals(EvidencePolicy.DEFAULT, policy(ReasoningRegime.HEALTHY))
    }

    @Test
    fun `truth channels never move regardless of regime`() {
        val shattered = ReasoningRegime(
            grounded = false,
            movement = 0.0,
            directionalStability = 0.0,
            uncertainty = 1.0
        )
        val weights = policy(shattered)
        assertEquals(1.0, weights.weightOf(EvidenceSource.ACCESSIBILITY), 0.0)
        assertEquals(1.0, weights.weightOf(EvidenceSource.USER), 0.0)
    }

    @Test
    fun `noisy channels damp monotonically as the regime degrades`() {
        fun ocrAt(d: Double) = policy(
            ReasoningRegime(grounded = true, movement = 1.0 - d, directionalStability = 1.0, uncertainty = 0.0)
        ).weightOf(EvidenceSource.OCR)
        assertTrue(ocrAt(0.0) > ocrAt(0.3))
        assertTrue(ocrAt(0.3) > ocrAt(0.7))
        assertTrue(ocrAt(0.7) > ocrAt(1.0))
    }

    @Test
    fun `floors hold and evidence is discounted never deleted`() {
        val worst = policy(
            ReasoningRegime(grounded = true, movement = 0.0, directionalStability = 0.0, uncertainty = 1.0)
        )
        assertEquals(StateOfThoughtPolicy.OCR_WEIGHT_FLOOR, worst.weightOf(EvidenceSource.OCR), 1e-9)
        assertEquals(StateOfThoughtPolicy.WORLD_MODEL_WEIGHT_FLOOR, worst.weightOf(EvidenceSource.WORLD_MODEL), 1e-9)
        assertTrue(worst.weightOf(EvidenceSource.OCR) > 0.0)
        assertTrue(worst.weightOf(EvidenceSource.WORLD_MODEL) > 0.0)
    }

    @Test
    fun `trust ordering accessibility over OCR over world model holds for every regime`() {
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { degradation ->
            val regime = ReasoningRegime(
                grounded = true,
                movement = 1.0 - degradation,
                directionalStability = 1.0 - degradation,
                uncertainty = degradation
            )
            val weights = policy(regime)
            assertTrue(weights.weightOf(EvidenceSource.ACCESSIBILITY) >= weights.weightOf(EvidenceSource.OCR))
            assertTrue(weights.weightOf(EvidenceSource.OCR) >= weights.weightOf(EvidenceSource.WORLD_MODEL))
        }
    }

    @Test
    fun `ungrounded regime caps trust even when every signal claims to be fine`() {
        // A loop that cannot see the screen yet reports perfect belief is precisely the case
        // the uncertainty floor exists for: seeing nothing is not believing confidently.
        val ungrounded = ReasoningRegime(
            grounded = false, movement = 1.0, directionalStability = 1.0, uncertainty = 0.0
        )
        assertTrue(ungrounded.degradation >= ReasoningRegime.GROUNDLESS_UNCERTAINTY_FLOOR)
        assertTrue(
            policy(ungrounded).weightOf(EvidenceSource.OCR) <
                StateOfThoughtPolicy.OCR_HEALTHY_WEIGHT
        )
    }

    @Test
    fun `regime rejects out of range signals`() {
        val invalidConstructions = listOf<() -> ReasoningRegime>(
            { ReasoningRegime(true, -0.01, 0.5, 0.5) },
            { ReasoningRegime(true, 1.01, 0.5, 0.5) },
            { ReasoningRegime(true, 0.5, -0.01, 0.5) },
            { ReasoningRegime(true, 0.5, 1.01, 0.5) },
            { ReasoningRegime(true, 0.5, 0.5, -0.01) },
            { ReasoningRegime(true, 0.5, 0.5, 1.01) }
        )
        invalidConstructions.forEach { build ->
            try {
                build()
                fail("expected rejection but construction succeeded")
            } catch (expected: IllegalArgumentException) {
                // require() is the contract; a clamp here would silently launder a caller bug.
            }
        }
    }

    @Test
    fun `single degraded dimension alone suppresses noisy support`() {
        val oscillating = ReasoningRegime(
            grounded = true, movement = 1.0, directionalStability = 0.3, uncertainty = 0.0
        )
        assertTrue(policy(oscillating).weightOf(EvidenceSource.OCR) < StateOfThoughtPolicy.OCR_HEALTHY_WEIGHT)
    }

    @Test
    fun `stuck regime stops repeat-read OCR from outgunning grounded counter-evidence`() {
        // The behaviour the policy exists for. The screen shows a mild accessibility signal
        // that work is still in progress, while a confident OCR read claims "done". When the
        // regime is healthy the OCR hit legitimately clears the confidence bar (0.648 vs 0.2
        // → 76%); when the run is stuck and oscillating — the exact situation where the same
        // screen gets re-read as if it were new corroboration — the same mix no longer clears
        // it (0.405 vs 0.2 → 67%), so completion demands stronger grounded support.
        val stuck = ReasoningRegime(
            grounded = true, movement = 0.0, directionalStability = 0.2, uncertainty = 0.6
        )
        val contested = listOf(
            ObservationEvidence("text:done", EvidenceSource.OCR, 0.9),
            ObservationEvidence("text:working", EvidenceSource.ACCESSIBILITY, 0.2)
        )
        assertEquals("text:done", BeliefReasoner.infer(contested).confidentFact(0.75))
        assertNull(BeliefReasoner.infer(contested, policy(stuck).weights).confidentFact(0.75))
    }

    @Test
    fun `grounded evidence still proves the goal under a degraded regime`() {
        // Damping is one-directional: it raises the corroboration bar for noisy channels,
        // never the accessibility tree itself.
        val stuck = ReasoningRegime(
            grounded = true, movement = 0.0, directionalStability = 0.2, uncertainty = 0.6
        )
        val grounded = listOf(
            ObservationEvidence("text:done", EvidenceSource.ACCESSIBILITY, 0.9),
            ObservationEvidence("text:working", EvidenceSource.OCR, 0.2)
        )
        assertEquals(
            "text:done",
            BeliefReasoner.infer(grounded, policy(stuck).weights).confidentFact(0.75)
        )
    }

    @Test
    fun `partial weight maps fall back to defaults instead of suppressing omitted sources`() {
        // Only OCR is overridden (to near silence). A hypothetical suppression-by-omission
        // semantics would leave "done" alone in the field and crown it; the documented
        // fallback keeps WORLD_MODEL at its default weight, so mild but real competition
        // keeps "working" ahead.
        val evidence = listOf(
            ObservationEvidence("text:done", EvidenceSource.OCR, 0.9),
            ObservationEvidence("text:working", EvidenceSource.WORLD_MODEL, 0.9)
        )
        val belief = BeliefReasoner.infer(evidence, mapOf(EvidenceSource.OCR to 0.1))
        assertEquals("text:working", belief.confidentFact(0.75))
    }
}
