package ai.arena.mobet.automation

/**
 * Static jump table and dynamic execution rails for bounded control flow
 * (docs/FRONTIER.md pillar 3).
 *
 * Control steps (`branch`, `repeatUntil`, `tryAlternates`) never touch the device
 * directly, so they get their own rail: a hop budget independent of the action budget.
 * Loops multiply what PlanValidator checked statically, so the dynamic rails mirror the
 * static ones — every device-affecting step still counts against `policy.maxActions`,
 * and every `repeatUntil` stops at its own `maxIterations`.
 *
 * All state lives here so the runner code in the step loop is a thin consumption layer
 * and the budget semantics are testable on the JVM without an emulator.
 */
class ControlFlow(steps: List<Step>) {

    /** Name → step index. Duplicate labels are a PlanValidator violation; the map also
     *  refuses to collapse them silently here in case a plan skips validation. */
    private val labelToIndex: Map<String, Int> = buildMap {
        steps.forEachIndexed { index, step ->
            val label = step.label ?: return@forEachIndexed
            require(label !in this) { "Duplicate step label: $label" }
            put(label, index)
        }
    }

    /** Index of the step a label points at, or null if the label does not exist. */
    fun jumpTarget(label: String): Int? = labelToIndex[label]

    private var controlHops = 0
    private var actingSteps = 0
    private val repeatIterations = mutableMapOf<Int, Int>()

    /**
     * Consumes one control-flow hop. False past [limit] — the signature of a probable
     * infinite loop (a branch pair with no interleaved action would otherwise spin until
     * the runtime budget, pointlessly re-reading the screen thousands of times).
     */
    fun consumeControlHop(limit: Int = MAX_CONTROL_HOPS): Boolean = ++controlHops <= limit

    /** Counts one dispatched device-affecting step against the workflow's action budget. */
    fun consumeAction(maxActions: Int): Boolean = ++actingSteps <= maxActions

    /**
     * Counts one jump-back of the `repeatUntil` step at [stepIndex]; false once that
     * step's own `maxIterations` is exhausted. Iteration state is per step position, so
     * two nested loops never share a counter.
     */
    fun consumeIteration(stepIndex: Int, maxIterations: Int): Boolean {
        val next = (repeatIterations[stepIndex] ?: 0) + 1
        repeatIterations[stepIndex] = next
        return next <= maxIterations
    }

    companion object {
        /**
         * Decide-only actions, billed to the hop rail. Action strings reach the runner
         * lowercased (parse normalizes them), so comparisons stay lowercase here.
         */
        val CONTROL_ACTIONS = setOf("branch", "repeatuntil", "tryalternates")

        /** Total control hops allowed in one run, regardless of runtime budget. */
        const val MAX_CONTROL_HOPS = 200
    }
}
