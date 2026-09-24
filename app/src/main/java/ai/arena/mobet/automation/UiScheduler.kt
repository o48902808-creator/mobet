package ai.arena.mobet.automation

/**
 * Clock/queue seam for WorkflowRunner.
 *
 * Android execution uses HandlerUiScheduler. A JVM runner can provide a
 * deterministic implementation that records or immediately advances delayed
 * work, without replacing the workflow state machine or sleeping in tests.
 */
interface UiScheduler {
    /** Monotonic time used for runtime and selector deadlines. */
    val nowMs: Long

    fun postDelayed(delayMs: Long, action: () -> Unit)
    fun removeCallbacksAndMessages()
}

/** Main-thread scheduler used by the Android accessibility service. */
class HandlerUiScheduler(
    private val handler: android.os.Handler =
        android.os.Handler(android.os.Looper.getMainLooper())
) : UiScheduler {
    override val nowMs: Long
        get() = android.os.SystemClock.uptimeMillis()

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ action() }, delayMs)
    }

    override fun removeCallbacksAndMessages() {
        handler.removeCallbacksAndMessages(null)
    }
}

/**
 * Compatibility name for integrations that refer to the runner rather than
 * the UI layer when naming their scheduler seam.
 */
typealias WorkflowScheduler = UiScheduler
