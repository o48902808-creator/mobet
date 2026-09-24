package ai.arena.mobet.automation

/**
 * A small, platform-neutral view of an interactive screen node.
 *
 * The workflow engine deliberately speaks this interface rather than
 * [android.view.accessibility.AccessibilityNodeInfo]. That keeps traversal and
 * interaction policy independent from the source of the screen tree: the live
 * Android adapter is used in production, while a deterministic tree can be
 * supplied by JVM tests or another driver later.
 *
 * Implementations own the details of their node handles. Callers should treat
 * [children] as a read-only snapshot and use the action methods rather than
 * reaching through the implementation.
 */
interface UiNode {
    /** The best short label for diagnostics (text, content description, or id). */
    val label: String?

    /** Visible or entered text, when the node exposes any. */
    val text: String?

    /** Platform view/resource id, without imposing an Android id type. */
    val viewId: String?

    /** Accessibility/content description, when present. */
    val description: String?

    /** Optional class/role label for inspection; implementations may use View. */
    val role: String get() = "View"

    /** Screen-space bounds. */
    val bounds: UiBounds

    val clickable: Boolean
    val editable: Boolean

    /** Optional capabilities used by diagnostics; fake nodes may leave them false. */
    val scrollable: Boolean get() = false
    val checkable: Boolean get() = false

    /** Child nodes in accessibility/tree order. */
    val children: List<UiNode>

    /** Click this node, including any implementation-specific ancestor fallback. */
    fun performClick(): Boolean

    fun focus(): Boolean
    fun scroll(): Boolean
    fun setText(value: String): Boolean
}

/**
 * Platform-neutral screen coordinates used by [UiNode].
 *
 * Keeping this value type here, instead of exposing android.graphics.Rect,
 * is what allows a fake node tree to run in a plain JVM test.
 */
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** A convenient semantic alias for callers that prefer the shorter name. */
typealias Bounds = UiBounds

/**
 * Releases a live Android node when this is the production adapter. Fake nodes
 * do not own a platform handle, so the operation is intentionally a no-op for
 * them. This is kept out of [UiNode] so node fakes do not need lifecycle code.
 */
internal fun UiNode.release() {
    (this as? AccessibilityUiNode)?.release()
}

/**
 * Adapter from one AccessibilityNodeInfo handle to the platform-neutral node
 * contract consumed by WorkflowRunner and ScreenInspector.
 *
 * A wrapper owns the handle passed to it. Child wrappers own the handles
 * returned by getChild(), and release() is idempotent so cleanup paths can be
 * conservative when a traversal exits early.
 */
class AccessibilityUiNode(
    private val info: android.view.accessibility.AccessibilityNodeInfo
) : UiNode {
    private var released = false

    override val label: String?
        get() = text ?: description ?: viewId?.substringAfterLast('/')

    override val text: String?
        get() = info.text?.toString()?.trim()?.takeIf(String::isNotBlank)

    override val viewId: String?
        get() = info.viewIdResourceName?.takeIf(String::isNotBlank)

    override val description: String?
        get() = info.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)

    override val role: String
        get() = info.className?.toString()?.substringAfterLast('.') ?: "View"

    override val bounds: UiBounds
        get() {
            val rect = android.graphics.Rect()
            info.getBoundsInScreen(rect)
            return UiBounds(rect.left, rect.top, rect.right, rect.bottom)
        }

    override val clickable: Boolean
        get() = !released && info.isClickable

    override val editable: Boolean
        get() = !released && info.isEditable

    override val scrollable: Boolean
        get() = !released && info.isScrollable

    override val checkable: Boolean
        get() = !released && info.isCheckable

    override val children: List<UiNode>
        get() {
            if (released) return emptyList()
            val result = ArrayList<UiNode>(info.childCount)
            for (index in 0 until info.childCount) {
                info.getChild(index)?.let { result += AccessibilityUiNode(it) }
            }
            return result
        }

    /**
     * Preserve the old runner behaviour: try the node first, then each clickable
     * ancestor until one accepts ACTION_CLICK. Parent handles are released as
     * they are walked and the original wrapper remains independently owned.
     */
    override fun performClick(): Boolean {
        if (released) return false
        var current: android.view.accessibility.AccessibilityNodeInfo? =
            android.view.accessibility.AccessibilityNodeInfo.obtain(info)
        while (current != null) {
            if (current.isClickable &&
                current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                current.recycle()
                return true
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return false
    }

    override fun focus(): Boolean =
        !released && info.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)

    /** The runner historically scrolls forward; retain that exact direction. */
    override fun scroll(): Boolean =
        !released && info.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    override fun setText(value: String): Boolean {
        if (released) return false
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        return info.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )
    }

    /** Releases this adapter's owned platform handle exactly once. */
    internal fun release() {
        if (!released) {
            released = true
            info.recycle()
        }
    }
}
