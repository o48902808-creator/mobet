package ai.arena.mobet.synthesis

/**
 * Typed intermediate representation for workflow synthesis.
 *
 * The generation engine never turns free text straight into JSON steps. Text is first parsed
 * into this small, closed IR, which is then *grounded* against a live accessibility snapshot and
 * lowered into workflow JSON. Keeping the IR explicit means every later stage (grounding,
 * robustness, risk, policy) operates on a finite set of node kinds instead of on strings, and a
 * model-produced program could be admitted at exactly this boundary without widening authority.
 */
sealed interface Intent {
    /** The original clause, preserved verbatim for the synthesis report and confirm messages. */
    val source: String
}

/** Tap a control identified by [target]; [navigational] clauses expect the screen to change. */
data class TapIntent(
    override val source: String,
    val target: String,
    val navigational: Boolean = false
) : Intent

/** Type [value] into the editable control identified by [target]. */
data class FillIntent(
    override val source: String,
    val target: String,
    val value: String
) : Intent

/** Scroll, optionally towards a named target. */
data class ScrollIntent(override val source: String, val target: String? = null) : Intent

/** Wait for a control identified by [target] to exist. */
data class WaitIntent(override val source: String, val target: String) : Intent

/** Unconditional pause. */
data class DelayIntent(override val source: String, val millis: Long) : Intent

data class BackIntent(override val source: String) : Intent

data class HomeIntent(override val source: String) : Intent

/** Switch apps; the package still has to survive the policy allowlist and the runner re-check. */
data class LaunchIntent(override val source: String, val packageName: String) : Intent

/** Explicit blocking confirmation authored by the user (risk confirmations are added later). */
data class ConfirmIntent(override val source: String, val message: String) : Intent

/**
 * Post-condition attached to the *previous* emitted step, lowered into its `expect` block.
 * Standalone verification is meaningless: the engine rejects a program that starts with one.
 */
data class VerifyIntent(
    override val source: String,
    val text: String,
    val present: Boolean
) : Intent

/** `if "X" appears then …` — lowered into a bounded `branch` with an explicit join label. */
data class ConditionalIntent(
    override val source: String,
    val whenText: String,
    val present: Boolean,
    val body: List<Intent>
) : Intent

/** `repeat … until "X" appears` — lowered into a backwards `repeatUntil` with a hard iteration cap. */
data class RepeatIntent(
    override val source: String,
    val untilText: String,
    val present: Boolean,
    val body: List<Intent>,
    val maxIterations: Int
) : Intent

/**
 * Deterministic, offline grammar: goal text → [Intent] program.
 *
 * The grammar is intentionally small and closed. Anything it does not recognise is an error with
 * the offending clause quoted, never a guess — an unrecognised clause silently dropped would
 * produce a workflow that does less than the user asked for while looking complete.
 */
object IntentGrammar {

    /** Hard ceiling on parsed clauses; the runner's own budgets are far lower still. */
    const val MAX_INTENTS = 40

    private const val MAX_GOAL_LENGTH = 2_000

    private val segmentSplit = Regex("[;\n]+")
    private val clauseSplit = Regex("(?i)\\s*,?\\s+(?:and\\s+then|then)\\s+")
    private val quoted = Regex("[\"“”']([^\"“”']+)[\"“”']")

    private val conditional = Regex(
        "(?i)^if\\s+(.+?)\\s+(?:is\\s+|has\\s+)?" +
            "(appears?|is\\s+present|is\\s+visible|is\\s+shown|is\\s+missing|is\\s+absent|" +
            "is\\s+not\\s+present|is\\s+not\\s+visible|disappears?)\\s*,?\\s+then\\s+(.+)$"
    )
    private val repeat = Regex(
        "(?i)^repeat\\s+(.+?)\\s+until\\s+(.+?)\\s*(?:\\s+max\\s+(\\d{1,2})(?:\\s+times)?)?$"
    )
    private val verify = Regex(
        "(?i)^(?:verify|expect|assert|check)\\s+(?:that\\s+)?(.+?)\\s*" +
            "(appears?|is\\s+present|is\\s+visible|is\\s+shown|is\\s+gone|is\\s+missing|" +
            "is\\s+absent|disappears?|is\\s+not\\s+present)?$"
    )
    private val delay = Regex("(?i)^(?:wait|pause|delay)\\s+(\\d{1,5})\\s*(ms|milliseconds?|s|secs?|seconds?)$")
    private val launch = Regex("(?i)^(?:launch|start app|open app)\\s+([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)$")
    private val fill = Regex(
        "(?i)^(?:fill|enter|type|input|set)\\s+(?:in\\s+|into\\s+)?(.+?)\\s+(?:with|to|as)\\s+(.+)$"
    )
    private val scroll = Regex("(?i)^scroll(?:\\s+(?:down|up))?(?:\\s+(?:to|for|until)\\s+(.+))?$")
    private val waitFor = Regex("(?i)^(?:wait\\s+for|wait\\s+until|find|locate|await)\\s+(.+)$")
    private val tap = Regex("(?i)^(?:tap|click|press|touch|select|choose|open|go\\s+to|navigate\\s+to)\\s+(.+)$")
    private val confirm = Regex("(?i)^(?:confirm|ask|prompt)\\s+(.+)$")

    private val navigationalVerbs = Regex("(?i)^(?:open|go\\s+to|navigate\\s+to)\\b")
    private val presenceSuffix = Regex(
        "(?i)\\s*(?:appears?|is\\s+present|is\\s+visible|is\\s+shown|is\\s+gone|is\\s+missing|" +
            "is\\s+absent|is\\s+not\\s+present|is\\s+not\\s+visible|disappears?)\\s*$"
    )
    private val absentWords = Regex("(?i)missing|absent|gone|not\\s+present|not\\s+visible|disappear")

    fun parse(goal: String): Result<List<Intent>> = runCatching {
        val text = goal.trim()
        require(text.isNotBlank()) { "Enter a goal" }
        require(text.length <= MAX_GOAL_LENGTH) { "Goal exceeds $MAX_GOAL_LENGTH characters" }

        val intents = mutableListOf<Intent>()
        segmentSplit.split(text).map(String::trim).filter(String::isNotBlank).forEach { segment ->
            intents += parseSegment(segment)
        }
        require(intents.isNotEmpty()) { "No actions found in goal" }
        require(intents.size <= MAX_INTENTS) {
            "Goal expands to ${intents.size} clauses; the limit is $MAX_INTENTS"
        }
        require(intents.first() !is VerifyIntent) {
            "A verification cannot be the first clause — it asserts the result of the step before it"
        }
        intents
    }

    private fun parseSegment(segment: String): List<Intent> {
        conditional.matchEntire(segment)?.let { match ->
            val body = parseBody(match.groupValues[3], "if")
            return listOf(
                ConditionalIntent(
                    source = segment,
                    whenText = unquote(match.groupValues[1]),
                    present = !absentWords.containsMatchIn(match.groupValues[2]),
                    body = body
                )
            )
        }
        repeat.matchEntire(segment)?.let { match ->
            val body = parseBody(match.groupValues[1], "repeat")
            val cap = match.groupValues[3].toIntOrNull() ?: 10
            val untilRaw = match.groupValues[2]
            return listOf(
                RepeatIntent(
                    source = segment,
                    untilText = unquote(untilRaw.replace(presenceSuffix, "")),
                    present = !absentWords.containsMatchIn(untilRaw),
                    body = body,
                    maxIterations = cap.coerceIn(1, 50)
                )
            )
        }
        return clauseSplit.split(segment).map(String::trim).filter(String::isNotBlank)
            .map(::parseClause)
    }

    /** Bodies are flat by construction: nesting a conditional inside a loop is not expressible. */
    private fun parseBody(source: String, owner: String): List<Intent> {
        val body = clauseSplit.split(source).map(String::trim).filter(String::isNotBlank)
            .map(::parseClause)
        require(body.isNotEmpty()) { "The $owner clause has no body: “$source”" }
        require(body.size <= 8) { "The $owner body has ${body.size} clauses; the limit is 8" }
        require(body.none { it is ConditionalIntent || it is RepeatIntent }) {
            "Nested control flow is not supported inside a $owner clause"
        }
        require(body.first() !is VerifyIntent) {
            "A $owner body cannot start with a verification"
        }
        return body
    }

    private fun parseClause(clause: String): Intent {
        val lower = clause.lowercase().trim().trimEnd('.')
        when (lower) {
            "back", "go back", "press back", "navigate back" -> return BackIntent(clause)
            "home", "go home", "press home" -> return HomeIntent(clause)
            "scroll", "scroll down", "scroll up" -> return ScrollIntent(clause)
        }
        delay.matchEntire(clause)?.let { match ->
            val amount = match.groupValues[1].toLong()
            val millis = if (match.groupValues[2].startsWith("m")) amount else amount * 1_000
            return DelayIntent(clause, millis.coerceIn(0, 10_000))
        }
        launch.matchEntire(clause)?.let { return LaunchIntent(clause, it.groupValues[1]) }
        confirm.matchEntire(clause)?.let { return ConfirmIntent(clause, unquote(it.groupValues[1]).take(160)) }
        verify.matchEntire(clause)?.let { match ->
            val target = unquote(match.groupValues[1])
            if (target.isNotBlank()) {
                return VerifyIntent(
                    source = clause,
                    text = target,
                    present = !absentWords.containsMatchIn(match.groupValues[2])
                )
            }
        }
        waitFor.matchEntire(clause)?.let { return WaitIntent(clause, requireTarget(unquote(it.groupValues[1]), clause)) }
        fill.matchEntire(clause)?.let { match ->
            val value = unquote(match.groupValues[2])
            require(value.isNotBlank()) { "Fill clause has no value: “$clause”" }
            return FillIntent(clause, requireTarget(unquote(match.groupValues[1]), clause), value)
        }
        scroll.matchEntire(clause)?.let { match ->
            return ScrollIntent(clause, match.groupValues[1].takeIf(String::isNotBlank)?.let(::unquote))
        }
        tap.matchEntire(clause)?.let { match ->
            return TapIntent(
                source = clause,
                target = requireTarget(unquote(match.groupValues[1]), clause),
                navigational = navigationalVerbs.containsMatchIn(clause.trim())
            )
        }
        // Bare quoted target, e.g. «"Save"», is read as a tap; anything else is an error.
        quoted.find(clause)?.let { match ->
            if (match.value.trim() == clause.trim()) return TapIntent(clause, match.groupValues[1])
        }
        throw IllegalArgumentException("Clause is not understood: “$clause”")
    }

    private fun requireTarget(target: String, clause: String): String {
        require(target.isNotBlank()) { "Clause has no target: “$clause”" }
        return target
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim().trimEnd('.', ',')
        val match = quoted.matchEntire(trimmed) ?: quoted.find(trimmed)
        return (match?.groupValues?.get(1) ?: trimmed).trim()
    }
}
