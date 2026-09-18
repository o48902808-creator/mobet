package ai.arena.mobet.agent

import ai.arena.mobet.automation.InspectedElement
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorResolverTest {
    private fun element(
        label: String,
        selector: String,
        matches: Int = 1,
        role: String = "Button"
    ) = InspectedElement(label, role, selector, matches, 90, "0,0–100,100")

    private fun snapshot(vararg elements: InspectedElement) =
        ScreenSnapshot("com.example.app", 0L, elements.toList())

    @Test
    fun healsRenamedLabel() {
        val healed = SelectorResolver.heal(
            Selector(text = "Network and internet"),
            snapshot(
                element("Network & internet", "text: Network & internet"),
                element("Display", "text: Display")
            )
        )
        assertNotNull(healed)
        assertEquals("Network & internet", healed!!.selector.text)
        assertTrue(healed.confidence >= 0.72)
    }

    @Test
    fun abstainsWhenNothingIsClose() {
        val healed = SelectorResolver.heal(
            Selector(text = "Payment methods"),
            snapshot(element("Display", "text: Display"), element("Sound", "text: Sound"))
        )
        assertNull(healed)
    }

    @Test
    fun abstainsOnAmbiguousLookAlikes() {
        val healed = SelectorResolver.heal(
            Selector(text = "Account settings"),
            snapshot(
                element("Account setting A", "text: Account setting A"),
                element("Account setting B", "text: Account setting B")
            )
        )
        assertNull(healed)
    }

    @Test
    fun healsViewIdByHumanizedName() {
        val healed = SelectorResolver.heal(
            Selector(viewId = "com.example.app:id/save_button"),
            snapshot(element("Save button", "viewId: com.example.app:id/btn_save_v2"))
        )
        assertNotNull(healed)
        assertEquals("com.example.app:id/btn_save_v2", healed!!.selector.viewId)
    }

    @Test
    fun duplicateMatchesArePenalized() {
        val unique = SelectorResolver.heal(
            Selector(text = "Settings"),
            snapshot(element("Settings", "text: Settings", matches = 1))
        )
        val duplicated = SelectorResolver.heal(
            Selector(text = "Settings"),
            snapshot(element("Settings", "text: Settings", matches = 4))
        )
        assertNotNull(unique)
        if (duplicated != null) {
            assertTrue(duplicated.confidence < unique!!.confidence)
        }
    }

    @Test
    fun blankSelectorCannotHeal() {
        assertNull(SelectorResolver.heal(Selector(), snapshot(element("Save", "text: Save"))))
    }
}
