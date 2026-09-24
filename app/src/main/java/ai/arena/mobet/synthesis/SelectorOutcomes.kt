package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.Selector
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

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

    /** Weight halves every this many days, so stale evidence fades instead of ruling forever. */
    const val HALF_LIFE_DAYS = 30.0

    /** Entries whose decayed weight falls below this are dropped on load. */
    private const val FORGET_BELOW = 0.05

    private const val MAX_ENTRIES = 256
    private const val DAY_MS = 86_400_000.0

    /**
     * Decayed observation weights rather than raw counts: an app that broke a selector six months
     * ago should stop being punished for it once the evidence ages out.
     */
    data class Outcome(
        val successes: Double = 0.0,
        val failures: Double = 0.0,
        val updatedAt: Long = 0L
    ) {
        val total: Double get() = successes + failures
    }

    /** Storage boundary, so JVM tests stay pure and Android supplies encrypted persistence. */
    interface OutcomeJournal {
        fun load(): String?
        fun save(value: String)
    }

    private var journal: OutcomeJournal? = null
    private var dirty = false

    private val stats = object : LinkedHashMap<String, Outcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Outcome>): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * Installs persistent storage and adopts whatever survived, decayed to today.
     *
     * Without this the loop is theoretical: tallies died with the process and almost never reached
     * [MIN_OBSERVATIONS] in real use. Persistence is encrypted and stores selector identity plus
     * decayed weights only — never screen text, entered values or secrets.
     */
    @Synchronized
    fun attach(value: OutcomeJournal) {
        journal = value
        val payload = value.load() ?: return
        runCatching {
            val root = JSONArray(payload)
            val now = System.currentTimeMillis()
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val key = item.optString("k").takeIf(String::isNotBlank) ?: continue
                val at = item.optLong("at", now)
                val factor = decayFactor(at, now)
                val successes = item.optDouble("s", 0.0) * factor
                val failures = item.optDouble("f", 0.0) * factor
                if (successes + failures < FORGET_BELOW) continue
                stats[key] = Outcome(successes, failures, at)
            }
        }
    }

    /** Persists pending changes; called at the end of a run rather than per observation. */
    @Synchronized
    fun flush() {
        val target = journal ?: return
        if (!dirty) return
        val array = JSONArray()
        stats.forEach { (key, outcome) ->
            array.put(
                JSONObject().put("k", key).put("s", outcome.successes)
                    .put("f", outcome.failures).put("at", outcome.updatedAt)
            )
        }
        runCatching { target.save(array.toString()) }.onSuccess { dirty = false }
    }

    @Synchronized
    fun recordSuccess(packageName: String?, selector: SelectorSpec?) = record(packageName, selector, true)

    @Synchronized
    fun recordFailure(packageName: String?, selector: SelectorSpec?) = record(packageName, selector, false)

    /** Convenience for the runner, which holds a typed [Selector] rather than a [SelectorSpec]. */
    fun specOf(selector: Selector): SelectorSpec? = when {
        selector.viewId != null -> SelectorSpec.of("viewId", selector.viewId!!)
        selector.description != null -> SelectorSpec.of("description", selector.description!!)
        selector.text != null -> SelectorSpec.of("text", selector.text!!)
        else -> null
    }

    @Synchronized
    override fun adjustment(packageName: String?, selector: SelectorSpec): Double {
        val outcome = stats[key(packageName, selector)] ?: return 0.0
        if (outcome.total < MIN_OBSERVATIONS) return 0.0
        val ratio = (outcome.successes - outcome.failures) / outcome.total
        return ratio * GroundingPriors.MAX_ADJUSTMENT
    }

    @Synchronized
    fun outcomeOf(packageName: String?, selector: SelectorSpec): Outcome? = stats[key(packageName, selector)]

    @Synchronized
    fun clear() {
        stats.clear()
        dirty = false
    }

    /** Test seam: drops the storage binding without touching in-memory state. */
    @Synchronized
    fun detach() {
        journal = null
        dirty = false
    }

    @Synchronized
    fun summary(): String {
        if (stats.isEmpty()) return "No selector history yet"
        val successes = stats.values.sumOf { it.successes }
        val failures = stats.values.sumOf { it.failures }
        return "${stats.size} selector(s) observed · ${"%.1f".format(successes)} resolved · " +
            "${"%.1f".format(failures)} failed (weights decay with a ${HALF_LIFE_DAYS.toInt()}-day half-life)"
    }

    private fun record(packageName: String?, selector: SelectorSpec?, success: Boolean) {
        val spec = selector ?: return
        val key = key(packageName, spec)
        val now = System.currentTimeMillis()
        val previous = stats[key] ?: Outcome(updatedAt = now)
        // Age the existing weights to "now" before adding, so a tally is always expressed in
        // today's units and old evidence cannot outvote fresh evidence indefinitely.
        val factor = decayFactor(previous.updatedAt, now)
        val aged = Outcome(previous.successes * factor, previous.failures * factor, now)
        stats[key] = if (success) {
            aged.copy(successes = aged.successes + 1.0)
        } else {
            aged.copy(failures = aged.failures + 1.0)
        }
        dirty = true
    }

    private fun decayFactor(from: Long, now: Long): Double {
        if (from <= 0L || now <= from) return 1.0
        val ageDays = (now - from) / DAY_MS
        return exp(-ageDays * kotlin.math.ln(2.0) / HALF_LIFE_DAYS)
    }

    private fun key(packageName: String?, selector: SelectorSpec): String =
        "${packageName.orEmpty()}|$selector"
}
