package ai.arena.mobet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Dempster–Shafer fusion upgrade (docs/FRONTIER.md pillar 3): exact expected values are
 * computed by hand from Dempster's rule over the simple-support focal family each fixture
 * builds, so these lock the combination, not a re-derivation of it.
 */
class BeliefFusionTest {

    private fun infer(vararg items: Triple<String, EvidenceSource, Double>): BeliefState =
        BeliefReasoner.infer(items.map { ObservationEvidence(it.first, it.second, it.third) })

    @Test
    fun `single weak evidence keeps its reading but honestly reports doubt`() {
        // One OCR read at 0.8: w = .576; q(p)=.576, Θ=.424, K=0.
        // probability = .576 + .424·1 = 1.0 (historic single-reading corner);
        // ambiguity = 1 − (.576 − 0) = .424.
        val belief = infer(Triple("airplane mode", EvidenceSource.OCR, .8))
        assertEquals(1, belief.hypotheses.size)
        assertEquals(1.0, belief.hypotheses[0].probability, 1e-9)
        assertEquals(.424, belief.ambiguity, 1e-9)
    }

    @Test
    fun `noise cannot dilute ground truth, it only raises doubt`() {
        // Accessibility at full confidence "a" vs OCR .9 "b": q(a)=.352, q(b)=0, K=.648.
        // Truth keeps probability 1 — the contradiction surfaces entirely as ambiguity.
        val belief = infer(
            Triple("a", EvidenceSource.ACCESSIBILITY, 1.0),
            Triple("b", EvidenceSource.OCR, .9)
        )
        assertEquals("a", belief.hypotheses[0].proposition)
        assertEquals(1.0, belief.hypotheses[0].probability, 1e-9)
        assertEquals(0.0, belief.hypotheses[1].probability, 1e-9)
        assertEquals(.648, belief.ambiguity, 1e-9)
    }

    @Test
    fun `symmetric weak evidence stays undecided`() {
        // OCR .5 "a" (w=.36) vs OCR .75 "b" (w=.54): q(a)=.36·.46=.1656, q(b)=.54·.64=.3456,
        // Θ=.2944, norm=.8056 — ambiguity = 1 − (.3456 − .1656) = .82, and the stronger
        // reading leads without ever claiming certainty.
        val belief = infer(
            Triple("a", EvidenceSource.OCR, .5),
            Triple("b", EvidenceSource.OCR, .75)
        )
        assertEquals("b", belief.hypotheses[0].proposition)
        assertEquals(.6761, belief.hypotheses[0].probability, 1e-3)
        assertEquals(.82, belief.ambiguity, 1e-9)
    }

    @Test
    fun `total conflict yields no victor and maximal doubt`() {
        // Two full-weight truth channels contradicting: q(a)=q(b)=Θ=0, K=1 → nothing to
        // hand to a completion quorum and nothing to act on.
        val belief = infer(
            Triple("a", EvidenceSource.ACCESSIBILITY, 1.0),
            Triple("b", EvidenceSource.USER, 1.0)
        )
        assertEquals(2, belief.hypotheses.size)
        assertTrue(belief.hypotheses.all { it.probability == 0.0 })
        assertEquals(1.0, belief.ambiguity, 1e-9)
    }

    @Test
    fun `corroboration compounds across channels`() {
        // The pre-upgrade fixture, re-read under D–S: accessibility .9 + world-model .7 on
        // "settings" vs OCR .9 on "settlings". support(settings)=.9406, support(settlings)=
        // .648; margin = .3310912 − .0384912 = .2926 → ambiguity .7074, and the corroborated
        // truth crosses the .75 threshold the old weighted average (.67) never reached.
        val belief = infer(
            Triple("settings", EvidenceSource.ACCESSIBILITY, .9),
            Triple("settlings", EvidenceSource.OCR, .9),
            Triple("settings", EvidenceSource.WORLD_MODEL, .7)
        )
        assertEquals("settings", belief.hypotheses.first().proposition)
        assertEquals(2, belief.hypotheses.size)
        assertEquals("settings", belief.confidentFact())
        assertEquals(.7074, belief.ambiguity, 1e-4)
    }

    @Test
    fun `a tightening regime sharpens belief instead of changing it`() {
        // The SoT policy path: discount the suspect channel (OCR .2) and the same evidence
        // converges — w(OCR)=.18 → margin .771292 − .010692 = .7606 → ambiguity .2394.
        // The ordering never flips under tightening; only hesitation changes.
        val evidence = listOf(
            ObservationEvidence("settings", EvidenceSource.ACCESSIBILITY, .9),
            ObservationEvidence("settlings", EvidenceSource.OCR, .9),
            ObservationEvidence("settings", EvidenceSource.WORLD_MODEL, .7)
        )
        val tight = BeliefReasoner.infer(
            evidence, BeliefReasoner.DEFAULT_WEIGHTS + (EvidenceSource.OCR to .2)
        )
        assertEquals("settings", tight.hypotheses.first().proposition)
        assertEquals(.2394, tight.ambiguity, 1e-4)
        val loose = BeliefReasoner.infer(evidence)
        assertTrue(tight.ambiguity < loose.ambiguity)
    }
}
