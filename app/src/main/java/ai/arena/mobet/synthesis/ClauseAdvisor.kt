package ai.arena.mobet.synthesis

/**
 * Turns "Clause is not understood" into actionable guidance.
 *
 * A closed grammar is the right call for a tool that drives someone's phone — but a bare rejection
 * teaches the user nothing, and the commonest failures are near-misses: a missing quote, `enter`
 * instead of `fill`, `click` instead of `tap`, `wait 3` instead of `wait 3s`. The advisor is
 * *diagnostic only*: it never repairs or re-interprets a clause, because silently guessing what
 * someone meant is exactly the behaviour this engine refuses elsewhere. It only ever appends a
 * suggestion to the error the user already sees.
 */
object ClauseAdvisor {

    /**
     * Verbs the grammar *already* accepts, so the advisor never second-guesses a clause that
     * failed for some other reason. Kept in sync with the regexes in [IntentGrammar].
     */
    private val accepted = setOf(
        "tap", "click", "press", "touch", "select", "choose", "open", "go", "navigate",
        "fill", "enter", "type", "input", "set",
        "verify", "expect", "assert", "check",
        "wait", "pause", "delay", "find", "locate", "await",
        "scroll", "repeat", "if", "back", "home", "launch", "start", "confirm", "ask", "prompt"
    )

    /** Verbs users reach for that the grammar does not accept, and the accepted equivalent. */
    private val synonyms = mapOf(
        "hit" to "tap", "push" to "tap", "tick" to "tap", "activate" to "tap",
        "write" to "fill", "put" to "fill", "paste" to "fill",
        "goto" to "open", "visit" to "open", "launchapp" to "open",
        "swipe" to "scroll", "slide" to "scroll", "flick" to "scroll",
        "ensure" to "verify", "confirmthat" to "verify",
        "sleep" to "wait", "hold" to "wait",
        "loop" to "repeat", "retry" to "repeat", "keep" to "repeat", "while" to "repeat"
    )

    private val forms = listOf(
        "tap \"Label\"",
        "open \"Label\"",
        "fill \"Field\" with \"value\"",
        "scroll  ·  scroll to \"Label\"",
        "wait for \"Label\"  ·  wait 3s",
        "verify \"Text\" appears  ·  verify \"Text\" disappears",
        "if \"Text\" appears then tap \"Label\"",
        "repeat scroll until \"Text\" appears max 5",
        "back  ·  home"
    )

    /**
     * Guidance for a rejected clause, or null when nothing specific can be said.
     *
     * Checks run cheapest-first and stop at the first confident diagnosis, so the user gets one
     * clear instruction rather than a wall of possibilities.
     */
    fun advise(clause: String): String? {
        val trimmed = clause.trim()
        if (trimmed.isEmpty()) return null

        unbalancedQuotes(trimmed)?.let { return it }
        malformedDuration(trimmed)?.let { return it }
        incompleteFill(trimmed)?.let { return it }
        misspelledVerb(trimmed)?.let { return it }
        return "Accepted clause forms: ${forms.joinToString("  ·  ")}"
    }

    /** Appends guidance to an existing grammar error message. */
    fun explain(message: String, clause: String): String =
        advise(clause)?.let { "$message\n\n$it" } ?: message

    private fun unbalancedQuotes(clause: String): String? {
        val straight = clause.count { it == '"' }
        val open = clause.count { it == '“' }
        val close = clause.count { it == '”' }
        return when {
            straight % 2 == 1 -> "There is an odd number of quotation marks — every target needs both, e.g. tap \"Save\"."
            open != close -> "A curly quote is unclosed: ${open} “ and ${close} ” in this clause."
            else -> null
        }
    }

    private fun misspelledVerb(clause: String): String? {
        val firstWord = clause.substringBefore(' ').lowercase().trim('“', '”', '"', '.', ',')
        if (firstWord.isEmpty()) return null
        synonyms[firstWord]?.let { replacement ->
            return "“$firstWord” is not a clause verb — use “$replacement”, e.g. ${example(replacement)}."
        }
        // An already-accepted verb failed for some other reason; guessing a different verb would
        // send the user down the wrong path.
        if (firstWord in accepted) return null
        val suggestions = setOf("tap", "open", "fill", "scroll", "wait", "verify", "repeat", "back", "home")
        val near = suggestions.firstOrNull { nearMiss(it, firstWord) } ?: return null
        return "Did you mean “$near”? e.g. ${example(near)}."
    }

    private fun malformedDuration(clause: String): String? {
        if (!clause.lowercase().startsWith("wait")) return null
        val rest = clause.substringAfter(' ', "").trim()
        if (rest.toLongOrNull() == null) return null
        return "A delay needs a unit: “wait ${rest}s” for seconds or “wait ${rest}ms” for milliseconds."
    }

    private fun incompleteFill(clause: String): String? {
        if (!clause.lowercase().startsWith("fill")) return null
        if (clause.contains(" with ", ignoreCase = true)) return null
        return "A fill clause needs a value: fill \"Field\" with \"value\"."
    }

    private fun example(verb: String): String = when (verb) {
        "fill" -> "fill \"Email\" with \"a@b.com\""
        "scroll" -> "scroll to \"Save\""
        "wait" -> "wait for \"Save\""
        "verify" -> "verify \"Saved\" appears"
        "if" -> "if \"Save\" appears then tap \"Save\""
        "repeat" -> "repeat scroll until \"Save\" appears max 5"
        "back", "home" -> verb
        else -> "$verb \"Save\""
    }

    /**
     * Damerau-Levenshtein distance of 1 or less: one insertion, deletion, substitution, or the
     * transposition of two adjacent characters. Transpositions matter because "tpa" for "tap" is
     * the single commonest typing slip, and plain Levenshtein scores it as two edits.
     */
    private fun nearMiss(target: String, candidate: String): Boolean {
        if (candidate.isEmpty() || candidate == target) return false
        // Below three characters a single edit relates almost any two words, so a suggestion
        // would be noise rather than help.
        if (target.length < 3 || candidate.length < 3) return false
        return damerauLevenshtein(target, candidate) <= 1
    }

    private fun damerauLevenshtein(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 1) return 2
        val distance = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) distance[i][0] = i
        for (j in 0..b.length) distance[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                distance[i][j] = minOf(
                    distance[i - 1][j] + 1,
                    distance[i][j - 1] + 1,
                    distance[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    distance[i][j] = minOf(distance[i][j], distance[i - 2][j - 2] + 1)
                }
            }
        }
        return distance[a.length][b.length]
    }
}
