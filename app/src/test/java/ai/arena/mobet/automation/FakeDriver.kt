package ai.arena.mobet.automation

/**
 * Deterministic scripted driver used by JVM workflow tests.
 *
 * Each call to root() materializes the current screen tree. Node actions mutate
 * the script rather than touching Android, which makes the execution seam
 * observable without an emulator.
 */
class FakeDriver(
    val packageName: String = "com.example.demo"
) : DeviceDriver {
    enum class Screen { LANDING, FORM, DONE }

    var screen: Screen = Screen.LANDING
        private set
    var enteredText: String? = null
        private set
    val launches = mutableListOf<String>()
    val clickedLabels = mutableListOf<String>()

    override fun root(): UiNode = when (screen) {
        Screen.LANDING -> tree(
            ScriptedNode(
                label = "Continue",
                text = "Continue",
                clickable = true,
                onClick = {
                    clickedLabels += "Continue"
                    screen = Screen.FORM
                    true
                }
            )
        )
        Screen.FORM -> tree(
            ScriptedNode(
                label = "Email",
                viewId = "form/email",
                description = "Email",
                editable = true,
                onSetText = { enteredText = it }
            ),
            ScriptedNode(
                label = "Save",
                text = "Save",
                clickable = true,
                onClick = {
                    clickedLabels += "Save"
                    screen = Screen.DONE
                    true
                }
            )
        )
        Screen.DONE -> tree(
            ScriptedNode(label = "Done", text = "Done")
        )
    }

    override fun launch(packageName: String): Boolean {
        launches += packageName
        return packageName == this.packageName
    }

    override fun back(): Boolean = true
    override fun home(): Boolean = true

    override fun performPointGesture(
        startX: Double,
        startY: Double,
        endX: Double?,
        endY: Double?,
        durationMs: Long,
        callback: (Boolean) -> Unit
    ) = callback(true)

    override fun captureScreen(callback: (Boolean, String) -> Unit) =
        callback(true, "fake-capture.png")

    override fun findVisualText(
        query: String,
        callback: (Boolean, String, Double, Double) -> Unit
    ) = callback(false, "Fake OCR does not contain $query", 0.0, 0.0)

    override fun activePackageName(): String = packageName
    override fun appVersion(packageName: String): String? = null

    private fun tree(vararg children: ScriptedNode): ScriptedNode =
        ScriptedNode(label = "root", children = children.toList())

    private class ScriptedNode(
        override val label: String?,
        override var text: String? = null,
        override val viewId: String? = null,
        override val description: String? = null,
        override val bounds: UiBounds = UiBounds(0, 0, 100, 40),
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val children: List<UiNode> = emptyList(),
        private val onClick: () -> Boolean = { false },
        private val onSetText: (String) -> Unit = {}
    ) : UiNode {
        override fun performClick(): Boolean = if (clickable) onClick() else false
        override fun focus(): Boolean = editable
        override fun scroll(): Boolean = false
        override fun setText(value: String): Boolean {
            if (!editable) return false
            text = value
            onSetText(value)
            return true
        }
    }
}

/** A clock and task queue that advances only when the test asks it to. */
class DeterministicScheduler(
    startAtMs: Long = 0L
) : UiScheduler {
    private data class Task(val atMs: Long, val sequence: Long, val action: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var nextSequence = 0L
    override var nowMs: Long = startAtMs
        private set

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        tasks += Task(nowMs + delayMs.coerceAtLeast(0L), nextSequence++, action)
    }

    override fun removeCallbacksAndMessages() {
        tasks.clear()
    }

    fun runUntilIdle(maxTasks: Int = 500) {
        var executed = 0
        while (tasks.isNotEmpty()) {
            check(++executed <= maxTasks) { "Fake scheduler exceeded $maxTasks tasks" }
            val next = tasks.minWith(compareBy<Task> { it.atMs }.thenBy { it.sequence })
            tasks.remove(next)
            nowMs = next.atMs
            next.action()
        }
    }
}
