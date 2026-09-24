package ai.arena.mobet.synthesis

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.policy.PlanValidator
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRecipesTest {

    private fun element(label: String, selector: String, role: String = "Button") =
        InspectedElement(label, role, selector, 1, 92, "0,0–10,10")

    private val signInScreen = ScreenSnapshot(
        "com.example.app", 0,
        listOf(
            element("Email", "viewId: com.example.app:id/email", role = "EditText"),
            element("Password", "viewId: com.example.app:id/password", role = "EditText"),
            element("Sign in", "text: Sign in"),
            element("Home", "text: Home")
        )
    )

    @Test
    fun everyRecipeDeclaresParametersAndExpandsToParsableClauses() {
        WorkflowRecipes.catalogue.forEach { recipe ->
            assertTrue(recipe.parameters.isNotEmpty())
            val values = recipe.parameters.associate { parameter ->
                parameter.key to if (parameter.secret) "app.password" else "Sign in"
            }
            val script = recipe.expand(values).getOrThrow()
            assertTrue("${recipe.id} did not parse", IntentGrammar.parse(script).isSuccess)
        }
    }

    @Test
    fun signInRecipeSynthesizesAConfirmedSecretFlow() {
        val recipe = WorkflowRecipes.byId("sign-in")!!
        val result = recipe.synthesize(
            mapOf(
                "userField" to "Email",
                "user" to "you@example.com",
                "passwordField" to "Password",
                "secret" to "app.password",
                "submit" to "Sign in",
                "evidence" to "Home"
            ),
            signInScreen
        ).getOrThrow()
        assertTrue(PlanValidator.validate(result.workflow).isEmpty())
        val fills = result.workflow.steps.filter { it.action == "fill" }
        assertTrue(fills.any { it.value == "{{secret:app.password}}" })
        assertTrue(result.workflow.steps.any { it.action == "confirm" })
    }

    @Test
    fun recipeParametersCannotInjectExtraClauses() {
        val recipe = WorkflowRecipes.byId("search")!!
        val result = recipe.expand(
            mapOf(
                "field" to "Search",
                "query" to "wifi\" then tap \"Delete account",
                "evidence" to "Results"
            )
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun missingParameterIsRejected() {
        val recipe = WorkflowRecipes.byId("search")!!
        assertTrue(recipe.expand(mapOf("field" to "Search")).isFailure)
    }

    @Test
    fun secretParameterMustBeASecretName() {
        val recipe = WorkflowRecipes.byId("sign-in")!!
        val result = recipe.expand(
            mapOf(
                "userField" to "Email",
                "user" to "you@example.com",
                "passwordField" to "Password",
                "secret" to "not a secret name",
                "submit" to "Sign in",
                "evidence" to "Home"
            )
        )
        assertTrue(result.isFailure)
    }
}
