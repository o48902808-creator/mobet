package ai.arena.mobet.synthesis

import org.json.JSONObject

/**
 * Hoists literal `fill` values into named workflow variables.
 *
 * A generated plan with the query or the account name baked into a step is single-use: editing it
 * later means hunting through JSON. Hoisting turns it into a template — `{{var:search_query}}` —
 * that the runner substitutes at execution time and that the user can retarget by editing one
 * obvious place. It also puts every user-supplied literal in one reviewable block.
 *
 * Deliberate exclusions: values that are already `{{var:…}}`/`{{secret:…}}` references (nothing to
 * hoist), and values that *look* like credentials — promoting a password to a plaintext variable
 * would make it more visible and more copyable, which is the opposite of the intent. Those stay
 * put and are flagged by [PlanQuality] instead.
 */
internal object PlanParameterizer {

    private val alreadyReference = Regex("\\{\\{(?:var|secret):[A-Za-z0-9_.-]+}}")
    private val sensitive = Regex("(?i)password|passcode|pin|otp|token|secret|cvv|card")
    private const val MAX_VARIABLES = 16

    data class Parameterized(val steps: List<JSONObject>, val variables: Map<String, String>)

    fun apply(steps: List<JSONObject>, notes: MutableList<SynthesisNote>): Parameterized {
        val variables = linkedMapOf<String, String>()
        val rewritten = steps.map { original ->
            val step = JSONObject(original.toString())
            val value = step.optString("value").takeIf(String::isNotBlank)
            if (step.optString("action").lowercase() != "fill" || value == null) return@map step
            if (alreadyReference.containsMatchIn(value)) return@map step
            if (sensitive.containsMatchIn(fieldCorpus(step))) return@map step
            if (variables.size >= MAX_VARIABLES) return@map step

            val name = uniqueName(baseName(step), variables.keys)
            variables[name] = value
            step.put("value", "{{var:$name}}")
        }
        if (variables.isNotEmpty()) {
            notes += SynthesisNote(
                "parameters",
                "${variables.size} literal value(s) hoisted into variables: ${variables.keys.joinToString()}"
            )
        }
        return Parameterized(rewritten, variables)
    }

    private fun fieldCorpus(step: JSONObject): String = SelectorSpec.KEYS
        .mapNotNull { key -> step.optString(key).takeIf(String::isNotBlank) }
        .joinToString(" ")

    private fun baseName(step: JSONObject): String {
        val label = SelectorSpec.KEYS.firstNotNullOfOrNull { key ->
            step.optString(key).takeIf(String::isNotBlank)
        }.orEmpty().substringAfterLast('/')
        val slug = label.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(24)
        return slug.ifBlank { "value" }
    }

    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var index = 2
        while ("${base}_$index" in taken) index += 1
        return "${base}_$index"
    }
}
