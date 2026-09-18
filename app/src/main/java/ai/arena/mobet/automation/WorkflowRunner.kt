package ai.arena.mobet.automation

import ai.arena.mobet.security.SecretStore
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

class WorkflowRunner(
    private val service: MobetAccessibilityService,
    private val log: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val secrets = SecretStore(service)
    private var cancelled = false
    private var workflow: Workflow? = null
    private var index = 0
    private var awaitingConfirmation = false
    private var nextActionApproved = false

    fun start(value: Workflow) {
        workflow = value
        log("Starting “${value.name}”")
        if (value.packageName != null && !service.launch(value.packageName)) {
            finish("Could not launch ${value.packageName}")
            return
        }
        handler.postDelayed(::executeCurrent, 700)
    }

    fun cancel(reason: String) {
        if (cancelled) return
        cancelled = true
        awaitingConfirmation = false
        handler.removeCallbacksAndMessages(null)
        log(reason)
    }

    fun confirmationResult(approved: Boolean) {
        if (!awaitingConfirmation || cancelled) return
        awaitingConfirmation = false
        if (approved) {
            log("Confirmation approved")
            nextActionApproved = true
            advance(200)
        } else finish("Confirmation denied")
    }

    private fun executeCurrent() {
        if (cancelled) return
        val flow = workflow ?: return
        if (index >= flow.steps.size) {
            finish("Completed ${flow.steps.size} steps")
            return
        }
        val raw = flow.steps[index]
        val step = expand(raw, flow.variables) ?: return
        if (step.ifText != null && !exists(Selector(text = step.ifText))) {
            log("Step ${index + 1}: skipped (ifText not present)")
            advance(0)
            return
        }
        if (step.unlessText != null && exists(Selector(text = step.unlessText))) {
            log("Step ${index + 1}: skipped (unlessText present)")
            advance(0)
            return
        }
        log("Step ${index + 1}/${flow.steps.size}: ${step.action}")
        val approved = nextActionApproved
        if (step.action != "confirm") nextActionApproved = false
        when (step.action) {
            "back" -> complete(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK), step)
            "home" -> complete(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), step)
            "delay" -> advance(step.delayMs)
            "confirm" -> {
                awaitingConfirmation = true
                service.requestConfirmation(step.message ?: "Allow the next workflow action?")
            }
            "tappoint", "swipe" -> {
                if (!approved) finish("${step.action} requires an immediately preceding confirmation")
                else {
                    val x = step.xPercent
                    val y = step.yPercent
                    if (x == null || y == null) finish("${step.action} requires xPercent and yPercent")
                    else if (step.action == "swipe" && (step.endXPercent == null || step.endYPercent == null))
                        finish("swipe requires endXPercent and endYPercent")
                    else service.performPointGesture(
                        x, y,
                        if (step.action == "swipe") step.endXPercent else null,
                        if (step.action == "swipe") step.endYPercent else null,
                        step.durationMs
                    ) { ok -> if (ok) advance(step.delayMs) else finish("Gesture was cancelled") }
                }
            }
            "capture" -> {
                if (!approved) finish("capture requires an immediately preceding confirmation")
                else service.captureScreen { ok, result ->
                    if (ok) { log("Screenshot saved privately: $result"); advance(step.delayMs) }
                    else finish(result)
                }
            }
            "ocrwait", "visualtap" -> {
                val query = step.selector.text
                if (!approved) finish("${step.action} requires an immediately preceding confirmation")
                else if (query == null) finish("${step.action} requires text")
                else service.findVisualText(query) { found, detail, x, y ->
                    log(detail)
                    if (!found) finish(detail)
                    else if (step.action == "ocrwait") advance(step.delayMs)
                    else service.performPointGesture(x, y, null, null, 120) { tapped ->
                        if (tapped) advance(step.delayMs) else finish("Visual tap was cancelled")
                    }
                }
            }
            "wait" -> seek(step, requireAction = false)
            "tap" -> seek(step, requireAction = true) { click(it) }
            "fill" -> seek(step, requireAction = true) { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                service.setText(node, step.value.orEmpty())
            }
            "scroll" -> seek(step, requireAction = true) { node ->
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            else -> finish("Unknown action: ${step.action}")
        }
    }

    private fun expand(step: Step, variables: Map<String, String>): Step? {
        fun resolve(source: String?): String? {
            source ?: return null
            var result = source
            Regex("\\{\\{var:([A-Za-z0-9_.-]+)}}").findAll(source).forEach {
                val name = it.groupValues[1]
                val value = variables[name] ?: run {
                    finish("Missing variable: $name")
                    return null
                }
                result = result.replace(it.value, value)
            }
            Regex("\\{\\{secret:([A-Za-z0-9_.-]+)}}").findAll(result).forEach {
                val name = it.groupValues[1]
                val value = secrets.get(name) ?: run {
                    finish("Missing or unreadable secret: $name")
                    return null
                }
                result = result.replace(it.value, value)
            }
            return result
        }
        val text = resolve(step.selector.text) ?: if (step.selector.text != null) return null else null
        val id = resolve(step.selector.viewId) ?: if (step.selector.viewId != null) return null else null
        val description = resolve(step.selector.description) ?: if (step.selector.description != null) return null else null
        val value = resolve(step.value) ?: if (step.value != null) return null else null
        val ifText = resolve(step.ifText) ?: if (step.ifText != null) return null else null
        val unlessText = resolve(step.unlessText) ?: if (step.unlessText != null) return null else null
        val message = resolve(step.message) ?: if (step.message != null) return null else null
        return step.copy(
            selector = Selector(text, id, description), value = value,
            ifText = ifText, unlessText = unlessText, message = message
        )
    }

    private fun seek(step: Step, requireAction: Boolean, action: (AccessibilityNodeInfo) -> Boolean = { true }, retry: Int = 0) {
        val started = SystemClock.uptimeMillis()
        fun attempt() {
            if (cancelled) return
            val node = find(service.root(), step.selector)
            if (node != null) {
                val ok = try { action(node) } finally { node.recycle() }
                if (ok || !requireAction) advance(step.delayMs)
                else retryOrFail(step, requireAction, action, retry, "Action failed")
            } else if (SystemClock.uptimeMillis() - started >= step.timeoutMs) {
                retryOrFail(step, requireAction, action, retry, "Timed out")
            } else handler.postDelayed(::attempt, 250)
        }
        attempt()
    }

    private fun retryOrFail(step: Step, requireAction: Boolean, action: (AccessibilityNodeInfo) -> Boolean, retry: Int, reason: String) {
        if (retry < step.retries) {
            log("$reason; retry ${retry + 1}/${step.retries}")
            handler.postDelayed({ seek(step, requireAction, action, retry + 1) }, 500)
        } else finish("$reason finding ${describe(step.selector)}")
    }

    private fun exists(selector: Selector): Boolean {
        val node = find(service.root(), selector) ?: return false
        node.recycle()
        return true
    }

    private fun find(root: AccessibilityNodeInfo?, selector: Selector): AccessibilityNodeInfo? {
        root ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val matches = (selector.text == null || node.text?.toString()?.contains(selector.text, true) == true) &&
                (selector.viewId == null || node.viewIdResourceName == selector.viewId) &&
                (selector.description == null || node.contentDescription?.toString()?.contains(selector.description, true) == true)
            if (matches && selector != Selector()) {
                queue.forEach { it.recycle() }
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            node.recycle()
        }
        return null
    }

    private fun click(original: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(original)
        while (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                node.recycle(); return true
            }
            val parent = node.parent
            node.recycle(); node = parent
        }
        return false
    }

    private fun complete(ok: Boolean, step: Step) {
        if (ok) advance(step.delayMs) else finish("${step.action} failed")
    }

    private fun advance(delay: Long) {
        index++
        handler.postDelayed(::executeCurrent, delay)
    }

    private fun finish(message: String) {
        cancelled = true
        awaitingConfirmation = false
        handler.removeCallbacksAndMessages(null)
        log(message)
    }

    private fun describe(selector: Selector) = when {
        selector.viewId != null -> "id “${selector.viewId}”"
        selector.text != null -> "text “${selector.text}”"
        selector.description != null -> "description “${selector.description}”"
        else -> "a selector (none was supplied)"
    }
}
