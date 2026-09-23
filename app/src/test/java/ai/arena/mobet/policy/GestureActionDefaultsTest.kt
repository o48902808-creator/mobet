package ai.arena.mobet.policy

import ai.arena.mobet.automation.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the secure default for coordinate- and vision-based actions.
 *
 * `swipe`, `tappoint`, `capture`, `ocrwait` and `visualtap` are dispatched by
 * [ai.arena.mobet.automation.WorkflowRunner] but are deliberately **absent** from
 * [AutomationPolicy.DEFAULT_ACTIONS]. That asymmetry looks like an oversight and has been
 * mistaken for one, so it is pinned here with the reasoning.
 *
 * Accessibility selectors describe *what* to act on and are re-verified against a live snapshot.
 * Gesture and OCR actions describe *where* on the glass to touch, or infer intent from rendered
 * pixels; neither survives a layout change, a different density, or a stylised font, and a
 * mis-grounded coordinate tap lands on whatever happens to be there. Requiring the author to
 * name them in `policy.allowedActions` — on top of the `allowVisualFallbacks` gate — means
 * nobody acquires them by accident, e.g. by omitting a policy block entirely.
 */
class GestureActionDefaultsTest {

    private val gestureAndVisionActions =
        setOf("swipe", "tappoint", "capture", "ocrwait", "visualtap")

    @Test
    fun defaultActionSetExcludesEveryGestureAndVisionAction() {
        val leaked = gestureAndVisionActions.filter { it in AutomationPolicy.DEFAULT_ACTIONS }
        assertEquals(
            "Coordinate- and pixel-based actions must stay out of the default action set; " +
                "they should require an explicit opt-in in policy.allowedActions.",
            emptyList<String>(),
            leaked
        )
    }

    @Test
    fun defaultActionSetIsExactlyTheApprovedVocabulary() {
        // Pins the whole set, so adding any action to the defaults is a deliberate decision.
        //
        // The three control actions (docs/FRONTIER.md pillar 3) were added deliberately, not
        // by drift: `branch`, `repeatUntil` and `tryAlternates` decide *where execution goes*
        // but never touch the device — every actual tap/fill/launch they steer still passes
        // the same confirm, allowlist and risk gates, and their dynamic rails (per-repeat
        // iteration caps, the 200-hop control budget, and the action budget applied at run
        // time) keep loops inside exactly the limits policy.maxActions expressed statically.
        // That is the same contract the selector vocabulary already lives under: opt-out is
        // still available by naming a smaller `policy.allowedActions`.
        assertEquals(
            setOf(
                "wait", "tap", "fill", "scroll", "delay", "confirm", "back", "home", "launch",
                "branch", "repeatuntil", "tryalternates"
            ),
            AutomationPolicy.DEFAULT_ACTIONS
        )
    }

    @Test
    fun aWorkflowWithoutAPolicyBlockCannotSwipe() {
        // The practical consequence: omitting `policy` entirely must not grant gesture access.
        val workflow = Workflow.parse(
            """
            {
              "name": "no policy block",
              "package": "com.example.app",
              "steps": [ { "action": "swipe", "direction": "up" } ]
            }
            """.trimIndent()
        )
        val violations = PlanValidator.validate(workflow)
        assertTrue(
            "swipe must be rejected when the author never opted into it",
            violations.any { it.message.contains("swipe") || it.message.contains("Visual fallback") }
        )
    }

    @Test
    fun optingInExplicitlyStillRequiresTheVisualFallbackFlag() {
        // Naming the action is necessary but not sufficient: allowVisualFallbacks still gates it,
        // so the two controls are independent rather than one implying the other.
        val workflow = Workflow.parse(
            """
            {
              "name": "opted in, flag off",
              "package": "com.example.app",
              "policy": {
                "allowedPackages": ["com.example.app"],
                "allowedActions": ["swipe"],
                "allowVisualFallbacks": false
              },
              "steps": [ { "action": "swipe", "direction": "up" } ]
            }
            """.trimIndent()
        )
        assertTrue(
            "allowVisualFallbacks must still be required",
            PlanValidator.validate(workflow).any { it.message.contains("Visual fallback") }
        )
    }

    @Test
    fun fullyOptedInSwipeStillNeedsAConfirmBecauseItScoresElevated() {
        // swipe scores exactly 30, and the tier boundary is `score < 30 -> LOW`, so it lands in
        // ELEVATED and requires an immediately preceding confirm. Opting in via policy does not
        // waive the risk rule; the two are independent.
        val withoutConfirm = Workflow.parse(
            """
            {
              "name": "opted in, no confirm",
              "package": "com.example.app",
              "policy": {
                "allowedPackages": ["com.example.app"],
                "allowedActions": ["swipe"],
                "allowVisualFallbacks": true
              },
              "steps": [ { "action": "swipe", "direction": "up" } ]
            }
            """.trimIndent()
        )
        assertTrue(
            "an ELEVATED swipe must require an adjacent confirmation",
            PlanValidator.validate(withoutConfirm).any { it.message.contains("confirm") }
        )
    }

    @Test
    fun fullyOptedInSwipeWithConfirmIsAccepted() {
        // Positive control: with the action allowed, the visual flag set, and a confirm in front,
        // the plan is valid. Without this the suite could pass by rejecting everything.
        val workflow = Workflow.parse(
            """
            {
              "name": "fully opted in",
              "package": "com.example.app",
              "policy": {
                "allowedPackages": ["com.example.app"],
                "allowedActions": ["swipe", "confirm"],
                "allowVisualFallbacks": true
              },
              "steps": [
                { "action": "confirm", "message": "Allow a swipe gesture?" },
                { "action": "swipe", "direction": "up" }
              ]
            }
            """.trimIndent()
        )
        assertEquals(emptyList<PolicyViolation>(), PlanValidator.validate(workflow))
    }
}
