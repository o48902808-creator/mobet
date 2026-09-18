package ai.arena.mobet.agent

import kotlin.math.max
import kotlin.math.min

/**
 * Pure, deterministic fuzzy text matching shared by the planner and the self-healing resolver.
 * Blends token-set Jaccard with normalized Levenshtein so both word-level paraphrases
 * ("Network and internet" vs "Network & internet") and small typos score well, while unrelated
 * labels stay clearly below the grounding thresholds.
 */
object FuzzyText {
    private val stopWords = setOf("and", "the", "a", "an", "of", "to", "for")

    fun normalize(value: String): String {
        val base = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val tokens = base.split(' ').filter { it.isNotBlank() && it !in stopWords }
        return if (tokens.isEmpty()) base else tokens.joinToString(" ")
    }

    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val left = x.split(' ').toSet()
        val right = y.split(' ').toSet()
        if (left == right) return 0.93
        val containment = if (x in y || y in x) {
            0.82 + 0.13 * (min(x.length, y.length).toDouble() / max(x.length, y.length))
        } else 0.0
        val jaccard = left.intersect(right).size.toDouble() / left.union(right).size
        val charSim = 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length)
        val blended = 0.55 * jaccard + 0.45 * charSim
        return maxOf(containment, blended, charSim).coerceIn(0.0, 1.0)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
