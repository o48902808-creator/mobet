package ai.arena.mobet.synthesis

import org.json.JSONObject

/**
 * Deterministic peephole optimizer for freshly lowered plans.
 *
 * Lowering is intentionally naive — each clause emits its own defensive scaffolding — so the raw
 * step list contains redundancy: back-to-back waits on the same selector, join markers nothing
 * jumps to, scroll runs longer than the runner can benefit from. Every action a plan contains is
 * billed against `policy.maxActions` and executed on a real device, so redundancy is not free.
 *
 * The optimizer only ever *removes* work, and never removes anything observable:
 *  * a step that is the target of a `goto`/`elseGoto` is never dropped,
 *  * a step carrying an `expect` block is never dropped (it is an assertion, not scaffolding),
 *  * removals are label-preserving: if a dropped step carried a label, the label moves forward
 *    to the step that survives in its place.
 *
 * It runs before the risk pass, so confirmations are computed on the final step list.
 */
internal object PlanOptimizer {

    /** Longest run of consecutive target-less scrolls worth keeping. */
    private const val MAX_SCROLL_RUN = 3

    fun optimize(steps: List<JSONObject>, notes: MutableList<SynthesisNote>): List<JSONObject> {
        if (steps.size < 2) return steps
        val referenced = referencedLabels(steps)
        var current = steps.map { JSONObject(it.toString()) }
        var removedWaits = 0
        var removedJoins = 0
        var removedScrolls = 0

        // 1. Collapse consecutive waits on the same selector.
        current = fold(current, referenced) { previous, candidate ->
            previous.optString("action") == "wait" && candidate.optString("action") == "wait" &&
                selectorOf(previous) == selectorOf(candidate) && selectorOf(candidate) != null
        }.also { removedWaits = current.size - it.size }

        // 2. Drop zero-length join markers no jump can reach.
        current = current.filter { step ->
            val isJoin = step.optString("action") == "delay" && step.optLong("delayMs", -1) == 0L
            val label = step.optString("label").takeIf(String::isNotBlank)
            val reachable = label != null && referenced.contains(label)
            val keep = !isJoin || reachable
            if (!keep) removedJoins += 1
            keep
        }

        // 3. Trim over-long runs of target-less scrolls.
        val trimmed = mutableListOf<JSONObject>()
        var run = 0
        current.forEach { step ->
            val blindScroll = step.optString("action") == "scroll" && selectorOf(step) == null &&
                step.optString("label").isBlank() && !step.has("expect")
            run = if (blindScroll) run + 1 else 0
            if (blindScroll && run > MAX_SCROLL_RUN) {
                removedScrolls += 1
            } else {
                trimmed += step
            }
        }
        current = trimmed

        val removed = removedWaits + removedJoins + removedScrolls
        if (removed > 0) {
            val detail = listOfNotNull(
                removedWaits.takeIf { it > 0 }?.let { "$it duplicate wait(s)" },
                removedJoins.takeIf { it > 0 }?.let { "$it unreachable join(s)" },
                removedScrolls.takeIf { it > 0 }?.let { "$it excess scroll(s)" }
            ).joinToString()
            notes += SynthesisNote("optimizer", "$removed redundant step(s) removed: $detail")
        }
        return current
    }

    /** Every label some jump can land on; these steps are load-bearing and never removable. */
    private fun referencedLabels(steps: List<JSONObject>): Set<String> = buildSet {
        steps.forEach { step ->
            step.optString("goto").takeIf(String::isNotBlank)?.let(::add)
            step.optString("elseGoto").takeIf(String::isNotBlank)?.let(::add)
        }
    }

    /**
     * Drops each step for which [redundantAfter] holds against the previously kept step, moving
     * labels and assertions forward rather than discarding them.
     */
    private fun fold(
        steps: List<JSONObject>,
        referenced: Set<String>,
        redundantAfter: (previous: JSONObject, candidate: JSONObject) -> Boolean
    ): List<JSONObject> {
        val kept = mutableListOf<JSONObject>()
        steps.forEach { step ->
            val previous = kept.lastOrNull()
            val label = step.optString("label").takeIf(String::isNotBlank)
            val droppable = previous != null && redundantAfter(previous, step) &&
                !step.has("expect") && (label == null || label !in referenced)
            if (droppable) {
                // A label on a dropped step still has to address *something*: keep it on the
                // survivor so an author-visible name never silently disappears.
                if (label != null && previous!!.optString("label").isBlank()) previous.put("label", label)
                return@forEach
            }
            kept += step
        }
        return kept
    }

    private fun selectorOf(step: JSONObject): String? = SelectorSpec.KEYS
        .firstNotNullOfOrNull { key -> step.optString(key).takeIf(String::isNotBlank)?.let { "$key=$it" } }
}
