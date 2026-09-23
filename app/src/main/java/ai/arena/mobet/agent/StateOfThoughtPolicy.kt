package ai.arena.mobet.agent

/**
 * The four regime questions Mobet asks about its own reasoning each cycle, mirroring the
 * dynamics-geometric state of *State of Thought* (SoT) reasoning (Gong et al., 2026,
 * arXiv:2609.16055). SoT reads these signals from an LLM's internal activations; Mobet has no
 * model, so it derives the analogous regime from signals the deterministic loop genuinely
 * produces. See docs/STATE_OF_THOUGHT.md for the research background and the full mapping.
 *
 *  - [grounded] — δ, organization: is the current screen grounded in accessibility evidence
 *    at all, or is the loop looking at speculation? An ungrounded regime cannot raise trust,
 *    only cap it ([StateOfThoughtPolicy] forces a high uncertainty floor).
 *  - [movement] — v: is the trajectory advancing? 1.0 with recent progress, decaying toward
 *    0.0 as recovery attempts and no-progress cycles accumulate. Repetition on a stuck screen
 *    is not corroboration.
 *  - [directionalStability] — c: is the trajectory directional or oscillating? 1.0 for a
 *    straight-line path, falling as the recent window revisits already-seen screens.
 *  - [uncertainty] — H: local doxastic uncertainty [0..1], the belief tracker's ambiguity.
 *    One cycle of lag is deliberate: the regime is a readout of the trajectory so far, never
 *    of the decision currently being made.
 */
data class ReasoningRegime(
    val grounded: Boolean,
    val movement: Double,
    val directionalStability: Double,
    val uncertainty: Double
) {
    init {
        require(movement in 0.0..1.0) { "movement must be in [0,1]" }
        require(directionalStability in 0.0..1.0) { "directionalStability must be in [0,1]" }
        require(uncertainty in 0.0..1.0) { "uncertainty must be in [0,1]" }
    }

    /** Degradation in any single dimension suppresses noisy support entirely on its own. */
    val degradation: Double
        get() = maxOf(
            1.0 - movement,
            1.0 - directionalStability,
            maxOf(uncertainty, if (grounded) 0.0 else GROUNDLESS_UNCERTAINTY_FLOOR)
        ).coerceIn(0.0, 1.0)

    companion object {
        /** A loop that cannot see the screen is at least this uncertain, whatever it believes. */
        const val GROUNDLESS_UNCERTAINTY_FLOOR = 0.75

        /** The healthy regime; the policy maps this to exactly [EvidencePolicy.DEFAULT]. */
        val HEALTHY = ReasoningRegime(grounded = true, movement = 1.0, directionalStability = 1.0, uncertainty = 0.0)
    }
}

/** A per-source weighting for [BeliefReasoner.infer], derived from a [ReasoningRegime]. */
data class EvidencePolicy(val weights: Map<EvidenceSource, Double>) {
    fun weightOf(source: EvidenceSource): Double = weights[source] ?: 0.0

    companion object {
        /** The static trust ordering — identical to [BeliefReasoner.DEFAULT_WEIGHTS]. */
        val DEFAULT = EvidencePolicy(BeliefReasoner.DEFAULT_WEIGHTS)
    }
}

/**
 * Deterministic analogue of SoT's evidence-organization operator (𝒮).
 *
 * The insight SoT isolates — *which historical evidence is allowed to support the next
 * decision should depend on the reasoning regime, not just on the evidence itself* — has a
 * direct safety reading on-device: when the run is stuck, looping, or ambiguous, the noisier
 * supports are most likely to be stale or wrong, and letting them keep stacking weight is how
 * an agent talks itself into a false "success". So under a degraded regime the policy damps
 * OCR and world-model evidence toward fixed floors.
 *
 * Invariants this code is written to keep (all locked by StateOfThoughtPolicyTest):
 *  1. Ground-truth channels (ACCESSIBILITY, USER) are regime-independent. Damping the
 *     accessibility tree relative to OCR in bad states would invert the trust order exactly
 *     when the noise is at its worst; truth channels never move from 1.0.
 *  2. The ordering ACCESSIBILITY ≥ OCR ≥ WORLD_MODEL holds for every regime.
 *  3. Evidence is discounted, never deleted: floors stay strictly above zero, so a degraded
 *     regime raises the corroboration bar rather than rendering a channel mute.
 *  4. The healthy regime is exactly [EvidencePolicy.DEFAULT] — the policy provably changes
 *     nothing when nothing is wrong.
 *
 * The stopping operator (𝒯) has no counterpart added here on purpose: Mobet's deliberate
 * answer is concrete rule failure — cycle/runtime budgets, settling abstention, dead ends —
 * not a state-estimated continuation signal, and a second, softer stopper beside the hard
 * budgets would duplicate control. Rationale in docs/STATE_OF_THOUGHT.md.
 */
object StateOfThoughtPolicy {

    const val OCR_HEALTHY_WEIGHT = 0.72
    const val OCR_WEIGHT_FLOOR = 0.45
    const val WORLD_MODEL_HEALTHY_WEIGHT = 0.58
    const val WORLD_MODEL_WEIGHT_FLOOR = 0.35

    /**
     * Builds a [ReasoningRegime] from signals the deterministic loop genuinely produces —
     * the on-device analogue of reading δ/v/c/H out of a frozen model's internals.
     *
     * Each mapping is deliberately simple and monotonic, because the regime only ever
     * *tightens* corroboration; a regime that could loosen trust would be a security bug:
     *  - movement starts at 1.0 (no evidence of stalling is not evidence of health either —
     *    but stalling must be provable from the trajectory before it may tighten anything)
     *    and decays with the trailing run length of identical screens and with total
     *    recovery attempts, both damped by 0.5-per-step-style factors.
     *  - directionalStability is the fraction of *distinct* screens in the recent window:
     *    a path that revisits itself is oscillating by definition.
     *  - grounded and uncertainty pass through from the caller (accessibility evidence
     *    presence and the belief tracker's ambiguity).
     */
    fun regime(
        grounded: Boolean,
        recentScreens: List<String>,
        totalRecoveryAttempts: Int,
        uncertainty: Double
    ): ReasoningRegime {
        val stallRun = recentScreens.asReversed().asSequence()
            .takeWhile { it == recentScreens.lastOrNull() }.count()
        val stalls = (stallRun - 1).coerceAtLeast(0)
        val movementByStall = 1.0 / (1.0 + 0.5 * stalls)
        val movement = movementByStall / (1.0 + 0.25 * totalRecoveryAttempts.coerceAtLeast(0))
        val stability = if (recentScreens.isEmpty()) 1.0
        else recentScreens.toSet().size.toDouble() / recentScreens.size
        return ReasoningRegime(
            grounded = grounded,
            movement = movement.coerceIn(0.0, 1.0),
            directionalStability = stability.coerceIn(0.0, 1.0),
            uncertainty = uncertainty.coerceIn(0.0, 1.0)
        )
    }

    fun evidencePolicy(regime: ReasoningRegime): EvidencePolicy {
        val d = regime.degradation
        fun dampen(healthy: Double, floor: Double): Double = healthy - (healthy - floor) * d
        return EvidencePolicy(
            mapOf(
                EvidenceSource.ACCESSIBILITY to 1.0,
                EvidenceSource.USER to 1.0,
                EvidenceSource.OCR to dampen(OCR_HEALTHY_WEIGHT, OCR_WEIGHT_FLOOR),
                EvidenceSource.WORLD_MODEL to dampen(WORLD_MODEL_HEALTHY_WEIGHT, WORLD_MODEL_WEIGHT_FLOOR)
            )
        )
    }
}
