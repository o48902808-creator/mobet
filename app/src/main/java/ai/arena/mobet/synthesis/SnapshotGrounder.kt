package ai.arena.mobet.synthesis

import ai.arena.mobet.agent.FuzzyText
import ai.arena.mobet.automation.InspectedElement

/** A single concrete selector field pair, e.g. `viewId: com.app:id/save`. */
data class SelectorSpec(val key: String, val value: String) {
    init {
        require(key in KEYS) { "Unsupported selector key: $key" }
    }

    override fun toString(): String = "$key=$value"

    companion object {
        val KEYS = setOf("text", "viewId", "description")

        /** Parses the `key: value` form produced by [ai.arena.mobet.automation.ScreenInspector]. */
        fun parse(selector: String): SelectorSpec? {
            val key = selector.substringBefore(':').trim()
            val value = selector.substringAfter(':').trim()
            if (key !in KEYS || value.isEmpty()) return null
            return SelectorSpec(key, value)
        }
    }
}

data class GroundedCandidate(
    val selector: SelectorSpec,
    val label: String,
    val role: String,
    /** Fuzzy similarity between the requested target and the on-screen label, 0..1. */
    val similarity: Double,
    /** Snapshot-reported selector confidence, 0..99 (lower when the selector matches many nodes). */
    val confidence: Int
) {
    /** Ranking score: similarity dominates, selector stability breaks ties. */
    val score: Double get() = similarity * 0.9 + (confidence / 100.0) * 0.1
}

sealed interface Grounding {
    /** Exactly one clearly-best candidate. */
    data class Resolved(val best: GroundedCandidate) : Grounding

    /** Several candidates within the ambiguity margin; the caller decides fail vs. alternates. */
    data class Ambiguous(val candidates: List<GroundedCandidate>) : Grounding

    /** Nothing on screen is close enough; [best] is reported so the user can see how close. */
    data class NotFound(val best: GroundedCandidate?) : Grounding
}

/**
 * Grounds a requested target against the live accessibility snapshot.
 *
 * Grounding is the engine's honesty boundary: a clause only becomes a step if the thing it talks
 * about is actually on the captured screen. Two failure modes are kept distinct on purpose —
 * *not found* (the user named something that is not there) and *ambiguous* (several equally good
 * matches, where guessing would be a coin flip on the user's device).
 */
object SnapshotGrounder {

    /** Minimum similarity for a candidate to be considered at all. */
    const val GROUNDING_THRESHOLD = 0.55

    /** Candidates within this score of the best one are treated as indistinguishable. */
    const val AMBIGUITY_MARGIN = 0.06

    /** Upper bound on alternates emitted for an ambiguous target. */
    const val MAX_ALTERNATES = 4

    fun ground(
        target: String,
        elements: List<InspectedElement>,
        editableOnly: Boolean = false
    ): Grounding {
        val pool = if (editableOnly) {
            elements.filter { it.role.contains("Edit", ignoreCase = true) }.ifEmpty { elements }
        } else {
            elements
        }
        val ranked = pool.asSequence()
            .mapNotNull { element ->
                val selector = SelectorSpec.parse(element.selector) ?: return@mapNotNull null
                GroundedCandidate(
                    selector = selector,
                    label = element.label,
                    role = element.role,
                    similarity = FuzzyText.similarity(target, element.label),
                    confidence = element.confidence
                )
            }
            // Deterministic total order: score, then selector text, so identical inputs always
            // produce byte-identical workflows regardless of snapshot iteration order.
            .sortedWith(compareByDescending<GroundedCandidate> { it.score }.thenBy { it.selector.toString() })
            .toList()

        val best = ranked.firstOrNull() ?: return Grounding.NotFound(null)
        if (best.similarity < GROUNDING_THRESHOLD) return Grounding.NotFound(best)

        val contenders = ranked.filter {
            it.similarity >= GROUNDING_THRESHOLD && best.score - it.score <= AMBIGUITY_MARGIN
        }
        // Candidates that resolve to the same selector are not ambiguous, they are duplicates.
        val distinct = contenders.distinctBy { it.selector.toString() }
        return if (distinct.size <= 1) {
            Grounding.Resolved(best)
        } else {
            Grounding.Ambiguous(distinct.take(MAX_ALTERNATES))
        }
    }
}
