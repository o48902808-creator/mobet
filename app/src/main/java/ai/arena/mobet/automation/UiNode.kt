package ai.arena.mobet.automation

import android.view.accessibility.AccessibilityNodeInfo

/**
 * One element of a screen, as the runner needs to see it.
 *
 * `WorkflowRunner` previously spoke `AccessibilityNodeInfo` directly in its traversal, matching,
 * clicking and text entry, which welded plan execution to one Android API. That API is also the
 * app's single largest platform risk: Android 17's Advanced Protection Mode revokes accessibility
 * access for anything not declared an accessibility tool. Behind this interface the same plans can
 * be executed by a different backend — a UiAutomator/Appium session, or a fake in a unit test —
 * without the execution semantics (risk gates, policy, healing, evidence checks) changing at all.
 *
 * Implementations own their native resources: [release] is called for every node the runner
 * obtains, mirroring the recycle discipline the accessibility backend requires.
 */
interface UiNode {
    val text: String?
    val viewId: String?
    val description: String?
    val className: String?
    val isClickable: Boolean
    val isEditable: Boolean
    val isVisible: Boolean

    val childCount: Int
    fun child(index: Int): UiNode?
    fun parent(): UiNode?

    fun performClick(): Boolean
    fun performFocus(): Boolean
    fun performScrollForward(): Boolean
    fun setText(value: String): Boolean

    /** Screen bounds as `left,top-right,bottom`, or null when the backend cannot report them. */
    fun boundsInScreen(): android.graphics.Rect?

    /** Releases any native handle. Safe to call more than once. */
    fun release()

    /** True when this node satisfies every non-null field of [selector]. */
    fun matches(selector: Selector): Boolean {
        if (selector == Selector()) return false
        return (selector.text == null || text?.contains(selector.text!!, ignoreCase = true) == true) &&
            (selector.viewId == null || viewId == selector.viewId) &&
            (selector.description == null || description?.contains(selector.description!!, ignoreCase = true) == true)
    }
}

/**
 * [UiNode] backed by a real accessibility node.
 *
 * The wrapper is deliberately thin and owns exactly one native node, so the recycle rules stay
 * where they always were rather than becoming the caller's problem.
 */
class AccessibilityUiNode(val node: AccessibilityNodeInfo) : UiNode {

    private var released = false

    override val text: String? get() = node.text?.toString()
    override val viewId: String? get() = node.viewIdResourceName
    override val description: String? get() = node.contentDescription?.toString()
    override val className: String? get() = node.className?.toString()
    override val isClickable: Boolean get() = node.isClickable
    override val isEditable: Boolean get() = node.isEditable
    override val isVisible: Boolean get() = node.isVisibleToUser

    override val childCount: Int get() = node.childCount
    override fun child(index: Int): UiNode? = node.getChild(index)?.let(::AccessibilityUiNode)
    override fun parent(): UiNode? = node.parent?.let(::AccessibilityUiNode)

    override fun performClick(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    override fun performFocus(): Boolean = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    override fun performScrollForward(): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    override fun setText(value: String): Boolean {
        val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun boundsInScreen(): android.graphics.Rect? =
        android.graphics.Rect().also(node::getBoundsInScreen)

    override fun release() {
        if (released) return
        released = true
        @Suppress("DEPRECATION")
        runCatching { node.recycle() }
    }
}
