package ai.arena.mobet.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class InspectedElement(
    val label: String,
    val role: String,
    val selector: String,
    val matches: Int,
    val confidence: Int,
    val bounds: String
)

data class ScreenSnapshot(
    val packageName: String,
    val capturedAt: Long,
    val elements: List<InspectedElement>,
    /** Visible accessibility labels, bounded and held only in the current in-memory snapshot. */
    val visibleLabels: Set<String> = emptySet()
)

/** Converts a live accessibility tree into a node-free diagnostic snapshot. */
object ScreenInspector {
    fun inspect(root: AccessibilityNodeInfo, packageName: String): ScreenSnapshot {
        val nodes = mutableListOf<NodeData>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        while (queue.isNotEmpty() && nodes.size < 250) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val data = NodeData(
                text = node.text?.toString()?.trim()?.takeIf(String::isNotBlank),
                id = node.viewIdResourceName?.takeIf(String::isNotBlank),
                description = node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank),
                role = node.className?.toString()?.substringAfterLast('.') ?: "View",
                actionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable,
                bounds = bounds
            )
            if (data.actionable || data.text != null || data.description != null) nodes += data
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            node.recycle()
        }
        queue.forEach(AccessibilityNodeInfo::recycle)

        val elements = nodes.filter { it.actionable }.mapNotNull { node ->
            val selector = when {
                node.id != null -> "viewId: ${node.id}"
                node.description != null -> "description: ${node.description}"
                node.text != null -> "text: ${node.text}"
                else -> return@mapNotNull null
            }
            val matches = nodes.count { candidate ->
                when {
                    node.id != null -> candidate.id == node.id
                    node.description != null -> candidate.description == node.description
                    else -> candidate.text == node.text
                }
            }
            val base = when {
                node.id != null -> 95
                node.description != null -> 82
                else -> 68
            }
            val confidence = (base - (matches - 1) * 18).coerceIn(15, 99)
            InspectedElement(
                label = node.text ?: node.description ?: node.id.orEmpty().substringAfterLast('/'),
                role = node.role,
                selector = selector,
                matches = matches,
                confidence = confidence,
                bounds = "${node.bounds.left},${node.bounds.top}–${node.bounds.right},${node.bounds.bottom}"
            )
        }
        val visibleLabels = nodes.flatMap { listOfNotNull(it.text, it.description) }
            .map { it.take(160) }.take(120).toSet()
        return ScreenSnapshot(packageName, System.currentTimeMillis(), elements, visibleLabels)
    }

    private data class NodeData(
        val text: String?, val id: String?, val description: String?, val role: String,
        val actionable: Boolean, val bounds: Rect
    )
}
