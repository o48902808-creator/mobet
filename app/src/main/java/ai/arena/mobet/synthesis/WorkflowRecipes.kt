package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.ScreenSnapshot

/** One parameter of a recipe. [secret] parameters are lowered to `{{secret:…}}` references. */
data class RecipeParameter(
    val key: String,
    val label: String,
    val hint: String,
    val secret: Boolean = false
)

/**
 * A reusable, parameterised authoring pattern.
 *
 * A recipe is *not* a second code path: it expands into the same goal DSL the grammar already
 * understands, so it inherits grounding, robustness, risk confirmations, policy synthesis and
 * validation unchanged. Adding a recipe can therefore never add authority — only convenience.
 */
data class WorkflowRecipe(
    val id: String,
    val title: String,
    val summary: String,
    val parameters: List<RecipeParameter>,
    private val script: (Map<String, String>) -> String
) {
    /** Renders the goal DSL for this recipe without touching the device. */
    fun expand(values: Map<String, String>): Result<String> = runCatching {
        val resolved = parameters.associate { parameter ->
            val value = values[parameter.key]?.trim().orEmpty()
            require(value.isNotBlank()) { "${parameter.label} is required" }
            require(value.length <= 120) { "${parameter.label} exceeds 120 characters" }
            require(!FORBIDDEN.containsMatchIn(value)) {
                "${parameter.label} contains characters that would break the generated clause"
            }
            val lowered = if (parameter.secret) {
                require(value.matches(SECRET_NAME)) {
                    "${parameter.label} must be a secret name (letters, digits, . _ -)"
                }
                "{{secret:$value}}"
            } else {
                value
            }
            parameter.key to lowered
        }
        script(resolved)
    }

    fun synthesize(
        values: Map<String, String>,
        snapshot: ScreenSnapshot,
        options: SynthesisOptions = SynthesisOptions()
    ): Result<SynthesizedWorkflow> = expand(values).mapCatching { goal ->
        WorkflowSynthesizer.synthesize(
            goal = goal,
            snapshot = snapshot,
            options = options.copy(name = options.name ?: title)
        ).getOrThrow()
    }

    private companion object {
        /** Quote and separator characters would re-open the grammar from inside a parameter. */
        val FORBIDDEN = Regex("[\"“”';\n\r{}]")
        val SECRET_NAME = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}

/** The built-in recipe catalogue. Deterministic, offline, and free of device effects. */
object WorkflowRecipes {

    val catalogue: List<WorkflowRecipe> = listOf(
        WorkflowRecipe(
            id = "search",
            title = "Search in this app",
            summary = "Focus the search field, type a query, and wait for results to render.",
            parameters = listOf(
                RecipeParameter("field", "Search field label", "Search"),
                RecipeParameter("query", "Query", "weather"),
                RecipeParameter("evidence", "Result evidence", "Results")
            )
        ) { values ->
            """
            wait for "${values["field"]}"
            fill "${values["field"]}" with "${values["query"]}"
            wait for "${values["evidence"]}"
            verify "${values["evidence"]}" appears
            """.trimIndent()
        },
        WorkflowRecipe(
            id = "sign-in",
            title = "Sign in with stored secrets",
            summary = "Fills a username and a secret-store password, then confirms before submitting.",
            parameters = listOf(
                RecipeParameter("userField", "Username field label", "Email"),
                RecipeParameter("user", "Username", "you@example.com"),
                RecipeParameter("passwordField", "Password field label", "Password"),
                RecipeParameter("secret", "Password secret name", "app.password", secret = true),
                RecipeParameter("submit", "Submit control", "Sign in"),
                RecipeParameter("evidence", "Signed-in evidence", "Home")
            )
        ) { values ->
            """
            fill "${values["userField"]}" with "${values["user"]}"
            fill "${values["passwordField"]}" with "${values["secret"]}"
            confirm "Submit sign-in?"
            tap "${values["submit"]}"
            wait for "${values["evidence"]}"
            verify "${values["evidence"]}" appears
            """.trimIndent()
        },
        WorkflowRecipe(
            id = "navigate-toggle",
            title = "Navigate and toggle a setting",
            summary = "Opens a settings section, scrolls to the control, and verifies the new state.",
            parameters = listOf(
                RecipeParameter("section", "Section", "Network & internet"),
                RecipeParameter("control", "Control", "Wi-Fi"),
                RecipeParameter("evidence", "Expected state text", "On")
            )
        ) { values ->
            """
            open "${values["section"]}"
            scroll to "${values["control"]}"
            tap "${values["control"]}"
            verify "${values["evidence"]}" appears
            """.trimIndent()
        },
        WorkflowRecipe(
            id = "scroll-find",
            title = "Scroll until something appears",
            summary = "Bounded scroll loop that stops as soon as the target text is on screen.",
            parameters = listOf(
                RecipeParameter("target", "Text to find", "Terms of service"),
                RecipeParameter("iterations", "Maximum scrolls", "10")
            )
        ) { values ->
            val cap = values["iterations"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
            """
            repeat scroll until "${values["target"]}" appears max $cap
            """.trimIndent()
        },
        WorkflowRecipe(
            id = "dismiss-then-act",
            title = "Dismiss a dialog, then act",
            summary = "Conditionally clears an interstitial before performing the real action.",
            parameters = listOf(
                RecipeParameter("dialogText", "Dialog text", "Not now"),
                RecipeParameter("dismiss", "Dismiss control", "Not now"),
                RecipeParameter("target", "Control to tap afterwards", "Continue")
            )
        ) { values ->
            """
            if "${values["dialogText"]}" appears then tap "${values["dismiss"]}"
            tap "${values["target"]}"
            """.trimIndent()
        }
    )

    fun byId(id: String): WorkflowRecipe? = catalogue.firstOrNull { it.id == id }
}
