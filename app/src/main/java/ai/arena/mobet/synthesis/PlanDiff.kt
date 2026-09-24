package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.Step
import ai.arena.mobet.automation.Workflow

/**
 * Step-level difference between the document in the editor and a plan about to replace it.
 *
 * Insert is destructive: generation, recipes, repair and crystallization all overwrite whatever
 * the user had. "Insert" is only an informed choice if the user can see what changes, so the
 * report shows a diff whenever the editor already holds a parsable plan.
 *
 * The algorithm is a plain longest-common-subsequence over canonical step signatures — small,
 * deterministic, and order-preserving, which matters because a workflow's meaning is its order.
 */
object PlanDiff {

    enum class Change { UNCHANGED, ADDED, REMOVED }

    data class Line(val change: Change, val text: String)

    data class Diff(val lines: List<Line>) {
        val added: Int get() = lines.count { it.change == Change.ADDED }
        val removed: Int get() = lines.count { it.change == Change.REMOVED }
        val unchanged: Int get() = lines.count { it.change == Change.UNCHANGED }
        val isIdentical: Boolean get() = added == 0 && removed == 0

        fun render(limit: Int = 40): String = buildString {
            appendLine("Changes     +$added / -$removed (${unchanged} unchanged)")
            if (isIdentical) {
                appendLine("  identical to the document in the editor")
                return@buildString
            }
            lines.take(limit).forEach { line ->
                val marker = when (line.change) {
                    Change.ADDED -> "+"
                    Change.REMOVED -> "-"
                    Change.UNCHANGED -> " "
                }
                appendLine("  $marker ${line.text}")
            }
            if (lines.size > limit) appendLine("  … ${lines.size - limit} more line(s)")
        }
    }

    /** Returns null when [before] is blank or unparsable — there is nothing meaningful to compare. */
    fun between(before: String, after: Workflow): Diff? {
        if (before.isBlank()) return null
        val old = runCatching { Workflow.parse(before) }.getOrNull() ?: return null
        return Diff(diff(old.steps.map(::signature), after.steps.map(::signature)))
    }

    /** Human-readable, canonical one-line rendering of a step. */
    fun signature(step: Step): String = buildString {
        append(step.action)
        val selector = listOfNotNull(
            step.selector.viewId?.let { "viewId=$it" },
            step.selector.description?.let { "description=$it" },
            step.selector.text?.let { "text=$it" }
        ).firstOrNull()
        selector?.let { append(' ').append(it) }
        step.packageName?.let { append(" package=").append(it) }
        step.value?.let { append(" value=").append(it) }
        step.message?.let { append(" message=").append(it.take(40)) }
        step.label?.let { append(" label=").append(it) }
        step.goto?.let { append(" goto=").append(it) }
        step.elseGoto?.let { append(" else=").append(it) }
        step.expect?.let { expectation ->
            append(" expect{")
            if (expectation.screenChange) append("screenChange ")
            expectation.textPresent?.let { append("present=").append(it).append(' ') }
            expectation.textAbsent?.let { append("absent=").append(it).append(' ') }
            expectation.packageIs?.let { append("package=").append(it) }
            append('}')
        }
    }.trim()

    /** Classic LCS table; plans are bounded to 200 steps, so the quadratic table is tiny. */
    private fun diff(before: List<String>, after: List<String>): List<Line> {
        val lengths = Array(before.size + 1) { IntArray(after.size + 1) }
        for (i in before.indices.reversed()) {
            for (j in after.indices.reversed()) {
                lengths[i][j] = if (before[i] == after[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
        }
        val lines = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < before.size && j < after.size) {
            when {
                before[i] == after[j] -> {
                    lines += Line(Change.UNCHANGED, before[i]); i++; j++
                }
                lengths[i + 1][j] >= lengths[i][j + 1] -> {
                    lines += Line(Change.REMOVED, before[i]); i++
                }
                else -> {
                    lines += Line(Change.ADDED, after[j]); j++
                }
            }
        }
        while (i < before.size) lines += Line(Change.REMOVED, before[i++])
        while (j < after.size) lines += Line(Change.ADDED, after[j++])
        return lines
    }
}
