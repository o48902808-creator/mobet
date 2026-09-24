package ai.arena.mobet.automation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The runner's sense of time and deferral.
 *
 * Execution is a self-rescheduling state machine: every wait, retry, timeout and inter-step delay
 * is a `postDelayed` onto the main looper, and every budget check reads `SystemClock`. That is
 * correct on a device and untestable anywhere else — a plan takes real wall-clock seconds to run,
 * so no JVM test could ever execute one end to end. Behind this seam a test can drive the same
 * state machine deterministically and instantly.
 */
interface RunScheduler {
    /** Monotonic milliseconds; only differences are meaningful. */
    fun now(): Long

    /** Runs [task] after [delayMs]. */
    fun post(delayMs: Long, task: () -> Unit)

    /** Drops every task not yet run. */
    fun cancelAll()
}

/** Production scheduler: the main looper, exactly as before. */
class HandlerScheduler(looper: Looper = Looper.getMainLooper()) : RunScheduler {
    private val handler = Handler(looper)
    override fun now(): Long = SystemClock.uptimeMillis()
    override fun post(delayMs: Long, task: () -> Unit) {
        handler.postDelayed(task, delayMs)
    }
    override fun cancelAll() = handler.removeCallbacksAndMessages(null)
}
