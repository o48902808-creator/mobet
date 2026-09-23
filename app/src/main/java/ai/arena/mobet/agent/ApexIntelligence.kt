package ai.arena.mobet.agent

/** Evidence channels stay explicit so OCR or a model can never masquerade as accessibility truth. */
enum class EvidenceSource { ACCESSIBILITY, OCR, WORLD_MODEL, USER }
data class ObservationEvidence(val proposition: String, val source: EvidenceSource, val confidence: Double)
data class BeliefHypothesis(val proposition: String, val probability: Double, val sources: Set<EvidenceSource>)
data class BeliefState(val hypotheses: List<BeliefHypothesis>, val ambiguity: Double) {
    fun confidentFact(minimum: Double = 0.75): String? = hypotheses.firstOrNull { it.probability >= minimum }?.proposition
}

/**
 * Dempster–Shafer evidence fusion (docs/FRONTIER.md pillar 3, final algorithm).
 *
 * Each item places mass w = confidence × channel weight on its proposition and 1−w on the
 * ignorance set Θ; this focal family combines in closed form under Dempster's rule, so the
 * implementation is exact, not sampled. Contradiction between propositions does not vanish
 * into the average as it did under weighted-sum fusion: it accumulates in the conflict mass
 * K, which is what ambiguity reports. Three consequences that are the point of the upgrade —
 *
 *  - corroboration compounds: independent channels supporting the same reading raise it
 *    instead of merely averaging in (a truth-channel read plus a weak world-model echo can
 *    legitimately cross the completion threshold);
 *  - noise cannot dilute ground truth: a full-weight accessibility or user fact leaves an
 *    OCR contradiction almost nowhere but K, so the truth keeps probability 1 and only the
 *    *doubt* rises;
 *  - ignorance stays visible: mass on Θ is allocated to hypotheses in proportion to their
 *    support, preserving the historic single-reading "probability 1" corner while the
 *    conflict-discounted margin governs how much the agent should hesitate.
 *
 * Under these focal sets the conflict-discounted margin (m₁−m₂)·(1−K) collapses exactly to
 * the plain unnormalized support margin q₁−q₂, which is what [ambiguity] reports as 1 − margin.
 */
object BeliefReasoner {
    /**
     * Static per-source trust. Ground-truth channels (the accessibility tree, the user) hold
     * full weight; noisier supports (OCR, the world model) count less toward corroboration.
     * This is the healthy-regime case of the state-conditioned policy in
     * [StateOfThoughtPolicy]; see docs/STATE_OF_THOUGHT.md for the reasoning-paradigm context.
     */
    val DEFAULT_WEIGHTS: Map<EvidenceSource, Double> = mapOf(
        EvidenceSource.ACCESSIBILITY to 1.0,
        EvidenceSource.USER to 1.0,
        EvidenceSource.OCR to 0.72,
        EvidenceSource.WORLD_MODEL to 0.58
    )

    /**
     * [weights] overrides per-source trust, typically from a state-conditioned policy. A source
     * omitted from the map falls back to its default rather than being silently suppressed —
     * a partial policy can tighten named channels without orphaning the rest.
     */
    fun infer(
        evidence: List<ObservationEvidence>,
        weights: Map<EvidenceSource, Double> = DEFAULT_WEIGHTS
    ): BeliefState {
        if (evidence.isEmpty()) return BeliefState(emptyList(), 1.0)
        val items = evidence.map {
            normalize(it.proposition) to (
                it.confidence.coerceIn(0.0, 1.0) *
                    (weights[it.source] ?: DEFAULT_WEIGHTS.getValue(it.source))
                ).coerceIn(0.0, 1.0)
        }
        // q(p) = (support for p) × (every other item abstains); abstainAll is q(Θ).
        // Computed with products only — never a ratio — so full-weight evidence dividing
        // the picture into pure conflict cannot fault.
        val abstainAll = items.fold(1.0) { acc, (_, w) -> acc * (1 - w) }
        val byProposition = items.groupBy({ it.first }, { it.second })
        val support = LinkedHashMap<String, Double>()
        byProposition.forEach { (proposition, ws) ->
            val ownSupport = 1 - ws.fold(1.0) { acc, w -> acc * (1 - w) }
            val othersAbstain = items.fold(1.0) { acc, (p, w) ->
                if (p == proposition) acc else acc * (1 - w)
            }
            support[proposition] = ownSupport * othersAbstain
        }
        val totalSupport = support.values.sum()
        val norm = totalSupport + abstainAll // = 1 − K
        val theta = if (norm > 1e-9) abstainAll / norm else 1.0
        val hypotheses = byProposition.keys.map { proposition ->
            val normalized = if (norm > 1e-9) support.getValue(proposition) / norm else 0.0
            val share = if (totalSupport > 1e-9) support.getValue(proposition) / totalSupport else 0.0
            BeliefHypothesis(
                proposition,
                probability = normalized + theta * share,
                sources = evidence
                    .filter { normalize(it.proposition) == proposition }
                    .map { it.source }.toSet()
            )
        }.sortedByDescending { it.probability }
        val margins = support.values.sortedDescending()
        val margin = (margins.getOrElse(0) { 0.0 } - margins.getOrElse(1) { 0.0 })
        return BeliefState(hypotheses, (1.0 - margin).coerceIn(0.0, 1.0))
    }

    private fun normalize(value: String) = value.trim().lowercase().replace(Regex("\\s+"), " ")
}

data class Subgoal(
    val description: String,
    val preconditions: Set<String>,
    val completionEvidence: Set<String>,
    val status: SubgoalStatus = SubgoalStatus.PENDING
)
enum class SubgoalStatus { PENDING, ACTIVE, COMPLETE, BLOCKED }

data class HierarchicalPlan(val goal: AgentGoal, val subgoals: List<Subgoal>)

/** Deterministic decomposition. Model suggestions may be supplied, but are treated only as hints. */
object HierarchicalPlanner {
    fun decompose(goal: AgentGoal, modelHints: List<ModelSubgoal> = emptyList()): HierarchicalPlan {
        val validatedHints = ModelOutputValidator.validateSubgoals(modelHints)
        val clauses = if (validatedHints.isNotEmpty()) validatedHints.map { it.description } else
            goal.description.split(Regex("(?i)\\bthen\\b|;|\\n")).map(String::trim).filter(String::isNotBlank)
        val subgoals = clauses.take(12).mapIndexed { index, clause ->
            Subgoal(clause,
                preconditions = if (index == 0) setOf("package:${goal.allowedPackage}") else setOf("subgoal:${index - 1}:complete"),
                completionEvidence = if (index == clauses.lastIndex) setOf(goal.successFact) else setOf("subgoal:$index:complete"))
        }
        return HierarchicalPlan(goal, subgoals.ifEmpty { listOf(Subgoal(goal.description, setOf("package:${goal.allowedPackage}"), setOf(goal.successFact))) })
    }
}

enum class FailureKind { STALE_SELECTOR, LOADING_DELAY, MODAL_INTERRUPTION, WRONG_APP, PERMISSION_GATE, DEAD_END, DEVICE_REJECTED }
data class RecoveryPolicy(val kind: FailureKind, val maxAttempts: Int, val action: RecoveryAction)
enum class RecoveryAction { WAIT, REPAIR_SELECTOR, DISMISS_MODAL, RETURN_TO_APP, ASK_USER, BACKTRACK, ABSTAIN }

object FailureClassifier {
    fun classify(before: AgentObservation, after: AgentObservation?, detail: String, elapsedMs: Long): FailureKind = when {
        after != null && after.packageName != before.packageName -> FailureKind.WRONG_APP
        Regex("(?i)permission|allow access|while using").containsMatchIn(detail + " " + after?.facts.orEmpty()) -> FailureKind.PERMISSION_GATE
        Regex("(?i)dialog|modal|popup").containsMatchIn(detail + " " + after?.facts.orEmpty()) -> FailureKind.MODAL_INTERRUPTION
        Regex("(?i)not found|selector|timed out").containsMatchIn(detail) -> FailureKind.STALE_SELECTOR
        elapsedMs < 3_000 && after?.screenId == before.screenId -> FailureKind.LOADING_DELAY
        detail.contains("reject", true) -> FailureKind.DEVICE_REJECTED
        else -> FailureKind.DEAD_END
    }
}

object RecoveryPolicies {
    fun forFailure(kind: FailureKind): RecoveryPolicy = when (kind) {
        FailureKind.LOADING_DELAY -> RecoveryPolicy(kind, 2, RecoveryAction.WAIT)
        FailureKind.STALE_SELECTOR -> RecoveryPolicy(kind, 1, RecoveryAction.REPAIR_SELECTOR)
        FailureKind.MODAL_INTERRUPTION -> RecoveryPolicy(kind, 1, RecoveryAction.DISMISS_MODAL)
        FailureKind.WRONG_APP -> RecoveryPolicy(kind, 1, RecoveryAction.RETURN_TO_APP)
        FailureKind.PERMISSION_GATE -> RecoveryPolicy(kind, 0, RecoveryAction.ASK_USER)
        FailureKind.DEAD_END -> RecoveryPolicy(kind, 1, RecoveryAction.BACKTRACK)
        FailureKind.DEVICE_REJECTED -> RecoveryPolicy(kind, 0, RecoveryAction.ABSTAIN)
    }
}

/** Strict schema boundary for optional model assistance. It cannot contain device operations. */
data class ModelSubgoal(val description: String, val rationale: String, val confidence: Double)
data class ModelRanking(val actionIds: List<String>, val confidence: Double)
interface ModelAssistant {
    fun proposeSubgoals(goal: AgentGoal): List<ModelSubgoal>
    fun rankSafeCandidates(goal: AgentGoal, observation: AgentObservation, allowedActionIds: Set<String>): ModelRanking
}

object ModelOutputValidator {
    fun validateSubgoals(items: List<ModelSubgoal>): List<ModelSubgoal> = items.take(12).filter {
        it.description.isNotBlank() && it.description.length <= 240 && it.rationale.length <= 400 &&
            it.confidence in 0.5..1.0 &&
            ContentTrustEngine.assess(it.description).trust != ContentTrust.UNTRUSTED_INSTRUCTION
    }
    fun validateRanking(output: ModelRanking, allowed: Set<String>): ModelRanking? =
        output.takeIf { it.confidence in 0.0..1.0 && it.actionIds.size <= 24 && it.actionIds.distinct().size == it.actionIds.size && it.actionIds.all(allowed::contains) }
}
