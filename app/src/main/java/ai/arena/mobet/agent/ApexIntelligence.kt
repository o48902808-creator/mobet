package ai.arena.mobet.agent

/** Evidence channels stay explicit so OCR or a model can never masquerade as accessibility truth. */
enum class EvidenceSource { ACCESSIBILITY, OCR, WORLD_MODEL, USER }
data class ObservationEvidence(val proposition: String, val source: EvidenceSource, val confidence: Double)
data class BeliefHypothesis(val proposition: String, val probability: Double, val sources: Set<EvidenceSource>)
data class BeliefState(val hypotheses: List<BeliefHypothesis>, val ambiguity: Double) {
    fun confidentFact(minimum: Double = 0.75): String? = hypotheses.firstOrNull { it.probability >= minimum }?.proposition
}

/** Weighted evidence fusion which preserves competing interpretations instead of forcing a label. */
object BeliefReasoner {
    private val sourceWeight = mapOf(
        EvidenceSource.ACCESSIBILITY to 1.0,
        EvidenceSource.USER to 1.0,
        EvidenceSource.OCR to 0.72,
        EvidenceSource.WORLD_MODEL to 0.58
    )

    fun infer(evidence: List<ObservationEvidence>): BeliefState {
        if (evidence.isEmpty()) return BeliefState(emptyList(), 1.0)
        val scores = evidence.groupBy { normalize(it.proposition) }.mapValues { (_, items) ->
            items.sumOf { it.confidence.coerceIn(0.0, 1.0) * sourceWeight.getValue(it.source) }
        }
        val total = scores.values.sum().coerceAtLeast(0.0001)
        val hypotheses = scores.map { (proposition, score) ->
            BeliefHypothesis(proposition, score / total,
                evidence.filter { normalize(it.proposition) == proposition }.map { it.source }.toSet())
        }.sortedByDescending { it.probability }
        val ambiguity = if (hypotheses.size < 2) 1.0 - hypotheses.first().probability
            else 1.0 - (hypotheses[0].probability - hypotheses[1].probability)
        return BeliefState(hypotheses, ambiguity.coerceIn(0.0, 1.0))
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
