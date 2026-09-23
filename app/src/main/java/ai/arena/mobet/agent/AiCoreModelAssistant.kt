package ai.arena.mobet.agent

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.generationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AICore (Gemini Nano) implementation of [ModelAssistant] — docs/FRONTIER.md pillar 1A.
 *
 * Every guarantee the app makes survives this class:
 *  - **Device-gated, off by default.** The client is created only when the user enabled model
 *    assistance for the run, and it survives creation only if AICore reports the feature
 *    AVAILABLE at that moment (wrong hardware, unlocked bootloader, or model not downloaded
 *    all read as absent). Absence is graceful: the deterministic [LocalStructuredModelAssistant]
 *    is the floor, exactly as the pillar demands.
 *  - **Zero new trust.** Model output passes [AiCorePromptCodec]'s strict parse and then
 *    [ModelOutputValidator]; a fully compromised model can do no more than reorder approved
 *    candidate ids or draft subgoal text that the content-trust engine screens — the same
 *    threat ceiling as any advisor (see docs/THREAT_MODEL.md, "On-device model assistance").
 *  - **No stalls.** Inference is synchronous per the contract, so it is hard-capped at
 *    [INFER_TIMEOUT_MS]; on a slow, quota-limited, or thermal-throttled model the loop falls
 *    back instead of blocking. One inference per wait, serialized on the client.
 *  - **Manifest invariant intact.** Inference runs in AICore's system processes; the app still
 *    declares no network permission, and the merged-manifest Gradle task proves it per build.
 */
class AiCoreModelAssistant private constructor(
    private val model: GenerativeModel,
    private val fallback: ModelAssistant
) : ModelAssistant {

    override fun proposeSubgoals(goal: AgentGoal): List<ModelSubgoal> {
        val parsed = infer(AiCorePromptCodec.subgoalPrompt(goal))
            ?.let(AiCorePromptCodec::parseSubgoals)
            ?.takeIf { it.isNotEmpty() }
            ?: return fallback.proposeSubgoals(goal)
        return ModelOutputValidator.validateSubgoals(parsed)
            .ifEmpty { fallback.proposeSubgoals(goal) }
    }

    override fun rankSafeCandidates(
        goal: AgentGoal,
        observation: AgentObservation,
        allowedActionIds: Set<String>
    ): ModelRanking {
        val candidates = observation.actions.filter { it.id in allowedActionIds }
        if (candidates.isEmpty()) return ModelRanking(emptyList(), 0.0)
        val ranked = infer(AiCorePromptCodec.rankingPrompt(goal, candidates))
            ?.let(AiCorePromptCodec::parseRankingIds)
            ?.let { ids ->
                ModelOutputValidator.validateRanking(
                    ModelRanking(ids, MODEL_RANK_CONFIDENCE), allowedActionIds
                )
            }
        return ranked ?: fallback.rankSafeCandidates(goal, observation, allowedActionIds)
    }

    /**
     * One bounded, serialized inference; any failure (timeout, quota, AICore split) reads as
     * null. The beta API surface is coroutine-native, so the synchronous contract bridges via
     * runBlocking + withTimeoutOrNull — the timeout cancels the coroutine, and kotlinx is
     * transitively guaranteed on the classpath (the API exposes Flow).
     */
    private fun infer(prompt: String): String? = runCatching {
        synchronized(model) {
            runBlocking {
                withTimeoutOrNull(INFER_TIMEOUT_MS) {
                    model.generateContent(prompt).candidates.firstOrNull()?.text
                }
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val ENGINE_NAME = "AICore · Gemini Nano"

        /** Hard caps so a slow or quota-limited on-device model can never stall the agent loop. */
        private const val INFER_TIMEOUT_MS = 4_000L
        private const val STATUS_TIMEOUT_MS = 2_000L

        /** Conservative fixed confidence: the model's own calibrated score is unavailable. */
        private const val MODEL_RANK_CONFIDENCE = 0.7

        @Volatile
        private var instance: AiCoreModelAssistant? = null

        /**
         * Process-wide gate: returns the AICore-backed assistant, created at most once, or the
         * deterministic local assistant when the device does not serve Gemini Nano right now.
         * Negative results are deliberately not cached — a model still downloading can become
         * available for the next run.
         */
        fun forEnabledRun(): ModelAssistant =
            instance ?: synchronized(this) {
                instance ?: create()?.also { instance = it } ?: LocalStructuredModelAssistant()
            }

        private fun create(): AiCoreModelAssistant? = runCatching {
            val model = Generation.getClient(generationConfig { })
            val status = runBlocking {
                withTimeoutOrNull(STATUS_TIMEOUT_MS) { model.checkStatus() }
            }
            if (status == FeatureStatus.AVAILABLE) {
                AiCoreModelAssistant(model, LocalStructuredModelAssistant())
            } else {
                runCatching { model.close() }
                null
            }
        }.getOrNull()
    }
}
