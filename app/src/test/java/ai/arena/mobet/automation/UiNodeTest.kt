package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inspection seam is deliberately usable without android.jar node handles.
 * Full runner execution belongs to the next stage; this test locks down the
 * node contract and snapshot semantics while the adapter is still small.
 */
class UiNodeTest {
    @Test
    fun inspectorTraversesAPlatformNeutralTree() {
        val root = FakeNode(
            label = "Screen",
            children = listOf(
                FakeNode("Continue", text = "Continue", clickable = true),
                FakeNode("Email", viewId = "form/email", editable = true),
                FakeNode("Details", description = "More details", clickable = true)
            )
        )

        val snapshot = ScreenInspector.inspect(root, "com.example.demo")

        assertEquals("com.example.demo", snapshot.packageName)
        assertEquals(
            setOf("Continue", "More details"),
            snapshot.visibleLabels
        )
        assertEquals(3, snapshot.elements.size)
        assertTrue(snapshot.elements.any { it.selector == "text: Continue" })
        assertTrue(snapshot.elements.any { it.selector == "viewId: form/email" })
        assertTrue(snapshot.elements.any { it.selector == "description: More details" })
    }

    @Test
    fun defaultOptionalCapabilitiesDoNotEnlargeTheFakeContract() {
        val node = FakeNode("plain")
        assertEquals(false, node.scrollable)
        assertEquals(false, node.checkable)
        assertEquals("View", node.role)
    }

    private class FakeNode(
        override val label: String?,
        override val text: String? = null,
        override val viewId: String? = null,
        override val description: String? = null,
        override val bounds: UiBounds = UiBounds(0, 0, 100, 40),
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val children: List<UiNode> = emptyList()
    ) : UiNode {
        override fun performClick() = clickable
        override fun focus() = editable
        override fun scroll() = false
        override fun setText(value: String) = editable
    }
}
