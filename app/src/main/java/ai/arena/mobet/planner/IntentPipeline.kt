package ai.arena.mobet.planner

import ai.arena.mobet.agent.AgentGoal

enum class RunMode { EXPLAIN, DRY_RUN, EXECUTE }
enum class IntentSource { TYPED, VOICE_TRANSCRIPT }

data class StructuredGoal(
    val description: String,
    val allowedPackage: String,
    val successFact: String,
    val source: IntentSource
)

data class AgentPlanPreview(
    val goal: StructuredGoal,
    val maxCycles: Int,
    val maxRuntimeMs: Long,
    val maxRisk: Int,
    val minConfidence: Double,
    val allowedActions: Set<String>,
    val requiredConfirmations: String,
    val haltConditions: List<String>
) {
    fun asAgentGoal(allowOcr: Boolean, allowModel: Boolean): AgentGoal = AgentGoal(
        description = goal.description,
        successFact = goal.successFact,
        allowedPackage = goal.allowedPackage,
        maxCycles = maxCycles,
        maxRisk = maxRisk,
        minConfidence = minConfidence,
        lookaheadExpansions = 32,
        maxRuntimeMs = maxRuntimeMs,
        allowOcrEvidence = allowOcr,
        allowModelAssistance = allowModel
    )

    fun explanation(mode: RunMode): String = buildString {
        appendLine("Mode: ${mode.name.lowercase().replace('_', ' ')}")
        appendLine("Goal: ${goal.description}")
        appendLine("Target package: ${goal.allowedPackage}")
        appendLine("Success evidence: ${goal.successFact}")
        appendLine("Input: ${if (goal.source == IntentSource.VOICE_TRANSCRIPT) "reviewed voice transcript" else "typed text"}")
        appendLine("Allowed actions: ${allowedActions.sorted().joinToString()}")
        appendLine("Budget: $maxCycles cycles · ${maxRuntimeMs / 1000}s · risk score ≤ $maxRisk")
        appendLine("Confidence floor: ${(minConfidence * 100).toInt()}%")
        appendLine("Confirmations: $requiredConfirmations")
        appendLine("Halt conditions:")
        haltConditions.forEach { appendLine("  • $it") }
        if (mode != RunMode.EXECUTE) append("Device effects: none")
    }
}

/** Deterministic transcript/text → structured goal → bounded candidate-plan boundary. */
object IntentToPlanPipeline {
    fun prepare(
        description: String,
        successFact: String,
        allowedPackage: String,
        source: IntentSource
    ): Result<AgentPlanPreview> = runCatching {
        val goal = description.trim().replace(Regex("\\s+"), " ")
        val evidence = successFact.trim().replace(Regex("\\s+"), " ")
        require(goal.isNotBlank()) { "Goal is required" }
        require(evidence.isNotBlank()) { "Completion evidence is required" }
        require(goal.length <= 500) { "Goal exceeds 500 characters" }
        require(evidence.length <= 200) { "Completion evidence exceeds 200 characters" }
        require(allowedPackage.matches(Regex("[A-Za-z][A-Za-z0-9_.]{2,254}"))) {
            "Target package is invalid"
        }
        AgentPlanPreview(
            goal = StructuredGoal(goal, allowedPackage, evidence, source),
            maxCycles = 20,
            maxRuntimeMs = 120_000,
            maxRisk = 29,
            minConfidence = 0.67,
            allowedActions = setOf("tap", "scroll", "back"),
            requiredConfirmations = "Actions above risk score 29 are blocked; existing confirmation gates cannot be bypassed",
            haltConditions = listOf(
                "package boundary changes",
                "confidence falls below 67%",
                "success evidence cannot be verified",
                "cycle or runtime budget is exhausted",
                "user presses Stop or intervenes",
                "accessibility service becomes unavailable"
            )
        )
    }
}
