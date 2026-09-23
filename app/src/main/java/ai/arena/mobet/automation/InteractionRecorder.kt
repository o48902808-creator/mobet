package ai.arena.mobet.automation

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Records taps and scrolls as portable selectors. Text entry is deliberately never captured. */
class InteractionRecorder(private val ownPackage: String) {
    private val steps = mutableListOf<JSONObject>()
    private var lastFingerprint = ""
    private var lastRecordedAt = 0L
    var active: Boolean = false
        private set

    fun start() {
        steps.clear()
        lastFingerprint = ""
        active = true
    }

    fun stop(): String {
        if (!active) return "[]"
        active = false
        val result = JSONArray(steps).toString(2)
        steps.clear()
        return result
    }

    fun observe(event: AccessibilityEvent): Boolean {
        if (!active || event.packageName?.toString() == ownPackage) return false
        // Bound the capture. A workflow can never exceed 200 steps (Workflow.MAX_STEPS), so
        // recording past the useful window would only grow an in-memory list during a long
        // session and import steps that fail policy anyway.
        if (steps.size >= MAX_RECORDED_STEPS) return false
        val action = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "tap"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            else -> return false
        }
        val source = event.source ?: return false
        val selector = selectorFor(source)
        source.recycle()
        selector ?: return false
        val step = JSONObject().put("action", action)
        selector.forEach { (key, value) -> step.put(key, value) }
        val fingerprint = step.toString()
        val now = SystemClock.uptimeMillis()
        if (fingerprint == lastFingerprint && now - lastRecordedAt < 350) return false
        steps += step
        lastFingerprint = fingerprint
        lastRecordedAt = now
        return true
    }

    private fun selectorFor(start: AccessibilityNodeInfo): Map<String, String>? {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(start)
        repeat(4) {
            val current = node ?: return null
            current.viewIdResourceName?.takeIf(String::isNotBlank)?.let {
                current.recycle(); return mapOf("viewId" to it)
            }
            current.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
                current.recycle(); return mapOf("description" to it)
            }
            current.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
                current.recycle(); return mapOf("text" to it)
            }
            val parent = current.parent
            current.recycle()
            node = parent
        }
        node?.recycle()
        return null
    }

    private companion object {
        /** Same ceiling as a runnable plan; see the note in [observe]. */
        const val MAX_RECORDED_STEPS = 50
    }
}
