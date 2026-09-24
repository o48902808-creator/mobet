package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.Selector

/** Source of small, bounded ranking adjustments applied to grounding candidates. */
interface GroundingPriors {
    /** Signed adjustment in score units; implementations must stay within ±[MAX_ADJUSTMENT]. */
    fun adjustment(packageName: String?, selector: SelectorSpec): Double

    companion object {
        /** Hard ceiling on how much learned history may move a candidate. */
        const val MAX_ADJUSTMENT = 0.05
    }
}

/** Default: generation ignores history entirely. */
object NoGroundingPriors : GroundingPriors {
    override fun adjustment(packageName: String?, selector: SelectorSpec): Double = 0.0
}

/**
 * Closes the loop between *execution* and *generation*.
 *
 * Until now the engine was open-loop: it could not tell that `text: Continue` times out on this
 * device every single run while `viewId: …/next` always resolves. The runner already learns this
 * the hard way; this records it as a per-package tally and lets grounding prefer selectors that
 * actually work.
 *
 * Deliberate limits, because "learned" must not mean "unbounded authority":
 *  * the adjustment is clamped to ±[GroundingPriors.MAX_ADJUSTMENT] and is applied to the
 *    *ranking score only* — the grounding threshold is evaluated on raw similarity, so history
 *    can never resurrect a target that is not really on screen, nor suppress one that is;
 *  * it needs at least [MIN_OBSERVATIONS] observations before it says anything at all;
 *  * storage is bounded and least-recently-used; nothing is persisted to disk by this class and
 *    no screen text, entered value or secret is retained — only selector identity and counts;
 *  * it is advisory input to a deterministic ranker, never a bypass of policy, risk or
 *    confirmation.
 */
object SelectorOutcomes : GroundingPriors {

    /** Observations required before history influences ranking at all. */
    const val MIN_OBSERVATIONS = 2

    private const val MAX_ENTRIES = 256

    data class Outcome(val successes: Int = 0, val failures: Int = 0) {
        val total: Int get() = successes + failures
    }

    private val stats = object : LinkedHashMap<String, Outcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Outcome>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun recordSuccess(packageName: String?, selector: SelectorSpec?) = record(packageName, selector, true)

    @Synchronized
    fun recordFailure(packageName: String?, selector: SelectorSpec?) = record(packageName, selector, false)

    /** Convenience for the runner, which holds a typed [Selector] rather than a [SelectorSpec]. */
    fun specOf(selector: Selector): SelectorSpec? = when {
        selector.viewId != null -> SelectorSpec("viewId", selector.viewId!!)
        selector.description != null -> SelectorSpec("description", selector.description!!)
        selector.text != null -> SelectorSpec("text", selector.text!!)
        else -> null
    }

    @Synchronized
    override fun adjustment(packageName: String?, selector: SelectorSpec): Double {
        val outcome = stats[key(packageName, selector)] ?: return 0.0
        if (outcome.total < MIN_OBSERVATIONS) return 0.0
        val ratio = (outcome.successes - outcome.failures).toDouble() / outcome.total
        return ratio * GroundingPriors.MAX_ADJUSTMENT
    }

    @Synchronized
    fun outcomeOf(packageName: String?, selector: SelectorSpec): Outcome? = stats[key(packageName, selector)]

    @Synchronized
    fun clear() = stats.clear()

    @Synchronized
    fun summary(): String {
        if (stats.isEmpty()) return "No selector history yet"
        val successes = stats.values.sumOf { it.successes }
        val failures = stats.values.sumOf { it.failures }
        return "${stats.size} selector(s) observed · $successes resolved · $failures failed"
    }

    private fun record(packageName: String?, selector: SelectorSpec?, success: Boolean) {
        val spec = selector ?: return
        val key = key(packageName, spec)
        val previous = stats[key] ?: Outcome()
        stats[key] = if (success) {
            previous.copy(successes = previous.successes + 1)
        } else {
            previous.copy(failures = previous.failures + 1)
        }
    }

    private fun key(packageName: String?, selector: SelectorSpec): String =
        "${packageName.orEmpty()}|$selector"
}
