package ai.arena.mobet.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the backend-neutral node the runner executes against. A fake implementation here
 * is the first proof that plan execution no longer requires an Android accessibility node.
 */
class UiNodeTest {

    private class FakeNode(
        override val text: String? = null,
        override val viewId: String? = null,
        override val description: String? = null,
        override val className: String? = "android.widget.TextView",
        override val isClickable: Boolean = false,
        override val isEditable: Boolean = false,
        override val isVisible: Boolean = true,
        private val children: List<FakeNode> = emptyList()
    ) : UiNode {
        var parentNode: FakeNode? = null
        var clicks = 0
        var released = 0
        var value: String? = null

        init {
            children.forEach { it.parentNode = this }
        }

        override val childCount: Int get() = children.size
        override fun child(index: Int): UiNode? = children.getOrNull(index)
        override fun parent(): UiNode? = parentNode
        override fun performClick(): Boolean {
            clicks++
            return isClickable
        }
        override fun performFocus(): Boolean = true
        override fun performScrollForward(): Boolean = true
        override fun setText(value: String): Boolean {
            this.value = value
            return isEditable
        }
        override fun boundsInScreen(): android.graphics.Rect? = null
        override fun release() {
            released++
        }
    }

    @Test
    fun everyNonNullSelectorFieldMustMatch() {
        val node = FakeNode(text = "Save changes", viewId = "app:id/save", description = "Save")
        assertTrue(node.matches(Selector(text = "Save")))
        assertTrue(node.matches(Selector(viewId = "app:id/save")))
        assertTrue(node.matches(Selector(text = "save", viewId = "app:id/save")))
        assertFalse(node.matches(Selector(text = "Save", viewId = "app:id/other")))
    }

    @Test
    fun textAndDescriptionMatchLooselyButViewIdIsExact() {
        val node = FakeNode(text = "Save changes", viewId = "app:id/save", description = "Save button")
        assertTrue(node.matches(Selector(text = "changes")))
        assertTrue(node.matches(Selector(description = "button")))
        assertFalse(node.matches(Selector(viewId = "id/save")))
    }

    @Test
    fun anEmptySelectorMatchesNothing() {
        // Guards the traversal: without this an empty selector would "find" the root.
        assertFalse(FakeNode(text = "anything").matches(Selector()))
    }

    @Test
    fun missingAttributesDoNotMatch() {
        val node = FakeNode(text = null, viewId = null)
        assertFalse(node.matches(Selector(text = "Save")))
        assertFalse(node.matches(Selector(viewId = "app:id/save")))
    }

    @Test
    fun childrenAndParentsFormANavigableTree() {
        val label = FakeNode(text = "Save")
        val button = FakeNode(className = "android.widget.Button", isClickable = true, children = listOf(label))
        val root = FakeNode(children = listOf(button))
        assertEquals(1, root.childCount)
        assertEquals(button, root.child(0))
        assertEquals(button, label.parent())
        assertEquals(null, root.parent())
    }

    @Test
    fun aFakeBackendCanRecordWhatAPlanWouldDo() {
        val field = FakeNode(viewId = "app:id/email", isEditable = true)
        assertTrue(field.setText("someone@example.com"))
        assertEquals("someone@example.com", field.value)
        field.release()
        assertEquals(1, field.released)
    }
}
