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

    /** Verbs users reach for that the grammar does not accept, and the accepted equivalent. */
    private val synonyms = mapOf(
        "click" to "tap", "press" to "tap", "push" to "tap", "select" to "tap",
        "choose" to "tap", "hit" to "tap", "touch" to "tap",
        "type" to "fill", "enter" to "fill", "input" to "fill", "write" to "fill",
        "set" to "fill", "put" to "fill",
        "goto" to "open", "navigate" to "open", "visit" to "open", "launch" to "open",
        "swipe" to "scroll", "slide" to "scroll",
        "check" to "verify", "assert" to "verify", "ensure" to "verify", "expect" to "verify",
        "pause" to "wait", "sleep" to "wait", "hold" to "wait",
        "loop" to "repeat", "retry" to "repeat", "keep" to "repeat"
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
        misspelledVerb(trimmed)?.let { return it }
        missingQuotes(trimmed)?.let { return it }
        malformedDuration(trimmed)?.let { return it }
        incompleteFill(trimmed)?.let { return it }
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
        synonyms[firstWord]?.let { accepted ->
            return "“$firstWord” is not a clause verb — use “$accepted”, e.g. ${example(accepted)}."
        }
        // One-character typos of an accepted verb ("tpa", "fil", "scrol").
        val accepted = setOf("tap", "open", "fill", "scroll", "wait", "verify", "if", "repeat", "back", "home")
        val near = accepted.firstOrNull { it != firstWord && editDistanceWithin1(it, firstWord) } ?: return null
        return "Did you mean “$near”? e.g. ${example(near)}."
    }

    private fun missingQuotes(clause: String): String? {
        val verb = clause.substringBefore(' ').lowercase()
        if (verb !in setOf("tap", "open", "fill", "wait", "verify")) return null
        if (clause.any { it == '"' || it == '“' }) return null
        val target = clause.substringAfter(' ', "").trim()
        if (target.isEmpty()) return "“$verb” needs a target: ${example(verb)}."
        return "Targets must be quoted: try $verb \"$target\"."
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

    /** True when [candidate] is within one insertion, deletion or substitution of [accepted]. */
    private fun editDistanceWithin1(accepted: String, candidate: String): Boolean {
        if (candidate.isEmpty()) return false
        if (kotlin.math.abs(accepted.length - candidate.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < accepted.length && j < candidate.length) {
            if (accepted[i] == candidate[j]) {
                i++; j++
                continue
            }
            if (++edits > 1) return false
            when {
                accepted.length > candidate.length -> i++
                accepted.length < candidate.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (accepted.length - i) + (candidate.length - j) <= 1
    }
}
