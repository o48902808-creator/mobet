package ai.arena.mobet.synthesis

import ai.arena.mobet.agent.AgentActionKind
import ai.arena.mobet.agent.AgentGoal
import ai.arena.mobet.agent.AgentRunResult
import ai.arena.mobet.agent.AgentStatus
import ai.arena.mobet.agent.AgentTransition
import org.json.JSONObject

/**
 * Crystallizes a *successful* autonomous run into a deterministic, reviewable workflow.
 *
 * Autonomous exploration is expensive and non-repeatable: it burns cycles rediscovering a route
 * the agent has already walked, and every re-run is another chance to take a different branch.
 * Crystallization converts the one path that worked into an ordinary workflow the user can read,
 * edit, schedule and re-run — turning a probabilistic capability into a deterministic one.
 *
 * Boundaries:
 *  * only `SUCCEEDED` runs crystallize — a plan distilled from a run that never verified its goal
 *    would encode a route to nowhere;
 *  * only the agent's retained path is used, so abandoned exploration, rejected actions and
 *    backtracked branches never appear in the output;
 *  * the run's own `successFact` becomes an `expect` assertion on the final step, so the replayed
 *    workflow verifies the same completion evidence the agent verified;
 *  * every step still passes through risk gating, least-privilege policy synthesis and
 *    `PlanValidator` — crystallizing cannot mint an action the author could not have written;
 *  * nothing screen-derived beyond the selectors the agent already acted on is retained.
 */
object AgentCrystallizer {

    data class CrystallizeOptions(
        /** Precede each replayed action with a wait on the same selector. */
        val insertWaits: Boolean = true,
        val defaultTimeoutMs: Long = 5_000,
        val defaultRetries: Int = 1,
        val maxSteps: Int = 80,
        val name: String? = null
    )

    fun crystallize(
        result: AgentRunResult,
        goal: AgentGoal,
        options: CrystallizeOptions = CrystallizeOptions()
    ): Result<SynthesizedWorkflow> = runCatching {
        require(result.status == AgentStatus.SUCCEEDED) {
            "Only a verified successful run can be crystallized (this run ${result.status.name.lowercase()}: ${result.explanation})"
        }
        require(result.successPath.isNotEmpty()) {
            "The run verified its goal without taking any action — there is nothing to replay"
        }

        val notes = mutableListOf<SynthesisNote>()
        val steps = mutableListOf<JSONObject>()
        var unusable = 0

        result.successPath.forEach { transition ->
            val step = lower(transition, options)
            if (step == null) {
                unusable += 1
                return@forEach
            }
            if (options.insertWaits) waitFor(step)?.let { wait ->
                val previous = steps.lastOrNull()
                val duplicate = previous != null &&
                    previous.optString("action") == "wait" &&
                    selectorOf(previous) == selectorOf(wait)
                if (!duplicate) steps += wait
            }
            steps += step
        }
        require(steps.isNotEmpty()) {
            "None of the agent's ${result.successPath.size} path action(s) carry a replayable selector"
        }
        require(unusable == 0) {
            // Silently dropping a step would produce a workflow that replays a *different*,
            // shorter route than the one that actually succeeded.
            "$unusable path action(s) have no replayable selector; the route cannot be replayed faithfully"
        }

        // Replay must verify the same evidence the agent verified, not merely finish.
        val last = steps.last()
        val expect = last.optJSONObject("expect") ?: JSONObject()
        expect.put("textPresent", goal.successFact)
        last.put("expect", expect)

        notes += SynthesisNote(
            "crystallize",
            "${result.successPath.size} retained path action(s) from a ${result.cycles}-cycle run " +
                "(${result.actions.size} total actions; exploration and backtracking discarded)"
        )
        notes += SynthesisNote(
            "crystallize",
            "final step asserts the run's own completion evidence: “${goal.successFact}”"
        )

        WorkflowAssembler.assemble(
            name = options.name ?: "Learned: ${goal.description.take(60)}",
            targetPackage = goal.allowedPackage,
            steps = steps,
            notes = notes,
            maxSteps = options.maxSteps
        ).getOrThrow()
    }

    private fun lower(transition: AgentTransition, options: CrystallizeOptions): JSONObject? {
        val action = transition.action
        return when (action.kind) {
            AgentActionKind.BACK -> JSONObject().put("action", "back")
            AgentActionKind.TAP, AgentActionKind.SCROLL -> {
                val selector = action.selector?.let(SelectorSpec::parse) ?: return null
                JSONObject()
                    .put("action", if (action.kind == AgentActionKind.TAP) "tap" else "scroll")
                    .put(selector.key, selector.value)
                    .put("timeoutMs", options.defaultTimeoutMs)
                    .put("retries", options.defaultRetries)
            }
        }
    }

    private fun waitFor(step: JSONObject): JSONObject? {
        if (step.optString("action") !in setOf("tap", "scroll")) return null
        val selector = SelectorSpec.KEYS
            .firstNotNullOfOrNull { key ->
                step.optString(key).takeIf(String::isNotBlank)?.let { SelectorSpec.of(key, it) }
            } ?: return null
        return JSONObject().put("action", "wait")
            .put(selector.key, selector.value)
            .put("timeoutMs", step.optLong("timeoutMs", 5_000))
    }

    private fun selectorOf(step: JSONObject): String? = SelectorSpec.KEYS
        .firstNotNullOfOrNull { key -> step.optString(key).takeIf(String::isNotBlank)?.let { "$key=$it" } }
}
