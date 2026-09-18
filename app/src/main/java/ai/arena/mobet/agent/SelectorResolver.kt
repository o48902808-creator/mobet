package ai.arena.mobet.agent

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Selector

data class HealedSelector(
    val selector: Selector,
    val label: String,
    val confidence: Double,
    val reason: String
)

/**
 * Self-healing selector resolution. When a step's selector no longer matches (an app update
 * renamed "Network & internet" to "Network and internet", a resource ID changed, etc.) this
 * resolver proposes the closest live element instead of failing the run.
 *
 * Guardrails:
 *  - only enabled when the workflow policy sets `allowSelfHealing: true`;
 *  - the runner additionally refuses to heal any step whose risk tier is above LOW;
 *  - a healed match must clear a confidence floor AND a margin over the runner-up, so the
 *    resolver abstains rather than guessing between look-alike targets;
 *  - every heal is logged with its confidence so the audit ledger shows exactly what happened.
 */
object SelectorResolver {
    private const val MIN_CONFIDENCE = 0.72
    private const val MIN_MARGIN = 0.08

    fun heal(failed: Selector, snapshot: ScreenSnapshot): HealedSelector? {
        val target = failed.text
            ?: failed.description
            ?: failed.viewId?.substringAfterLast('/')?.replace(Regex("[_\\-.]+"), " ")
            ?: return null
        if (FuzzyText.normalize(target).isBlank()) return null

        val ranked = snapshot.elements
            .map { it to score(target, it) }
            .sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second < MIN_CONFIDENCE) return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && runnerUp.first.selector != best.first.selector &&
            best.second - runnerUp.second < MIN_MARGIN
        ) return null

        val selector = toSelector(best.first) ?: return null
        val percent = (best.second * 100).toInt()
        return HealedSelector(
            selector = selector,
            label = best.first.label,
            confidence = best.second,
            reason = "healed to “${best.first.label}” via ${best.first.selector.substringBefore(':')} ($percent%)"
        )
    }

    fun toSelector(element: InspectedElement): Selector? {
        val key = element.selector.substringBefore(':')
        val value = element.selector.substringAfter(':').trim()
        if (value.isBlank()) return null
        return when (key) {
            "viewId" -> Selector(viewId = value)
            "description" -> Selector(description = value)
            "text" -> Selector(text = value)
            else -> null
        }
    }

    private fun score(target: String, element: InspectedElement): Double {
        val labelScore = FuzzyText.similarity(target, element.label)
        val idScore = if (element.selector.startsWith("viewId:")) {
            FuzzyText.similarity(
                target,
                element.selector.substringAfterLast('/').replace(Regex("[_\\-.]+"), " ")
            )
        } else 0.0
        val uniqueness = if (element.matches <= 1) 0.03 else -0.06 * (element.matches - 1)
        return (maxOf(labelScore, idScore) + uniqueness).coerceIn(0.0, 1.0)
    }
}
