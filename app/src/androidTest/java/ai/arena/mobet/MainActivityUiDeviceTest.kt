package ai.arena.mobet

import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test

/**
 * View-level verification of the v0.7.0 control surface on a real window manager.
 *
 * The connected test environment has no accessibility service enabled for Mobet, which makes
 * the disabled posture the deterministic starting state — exactly the state a fresh install
 * shows, and the one the walkthrough's step 1 moves out of. These assertions lock the UI
 * contract docs/TESTING_WALKTHROUGH.md teaches rather than the visuals.
 */
class MainActivityUiDeviceTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun serviceCardReflectsDisabledServiceAndGatesTheRunButton() {
        onView(withId(R.id.serviceState)).check(matches(withText(R.string.service_disabled)))
        onView(withId(R.id.openAccessibility)).check(matches(isDisplayed()))
        // The run gate must not be silently tappable until the service is connected.
        onView(withId(R.id.runWorkflow)).check(matches(not(isEnabled())))
    }

    @Test
    fun editorIsPreloadedWithTheBundledSettingsDemo() {
        onView(withId(R.id.editor))
            .check(matches(withText(containsString("Android Settings demo"))))
    }

    @Test
    fun editorDraftSurvivesActivityRecreation() {
        val marker = "rotation-marker-42cc"
        activityRule.scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.editor)
                .setText("{ \"name\": \"$marker\", \"steps\": [ { \"action\": \"delay\" } ] }")
        }
        // Same lifecycle the system uses for a rotation; onPause must have persisted the draft.
        activityRule.scenario.recreate()
        onView(withId(R.id.editor)).check(matches(withText(containsString(marker))))
    }

    @Test
    fun dryRunOpensTheCounterfactualReportWithoutTouchingTheDevice() {
        onView(withId(R.id.dryRun)).perform(scrollTo(), click())
        onView(withText(containsString("Simulated against the last snapshot")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun validatePolicyApprovesTheBundledDemo() {
        onView(withId(R.id.validatePolicy)).perform(scrollTo(), click())
        onView(withText(containsString("Approved by policy")))
            .check(matches(isDisplayed()))
    }

    @Test
    fun fullLifecycleRecreateKeepsRunGateConsistent() {
        activityRule.scenario.recreate()
        onView(withId(R.id.runWorkflow)).check(matches(not(isEnabled())))
        onView(withId(R.id.serviceState)).check(matches(withText(R.string.service_disabled)))
    }
}
