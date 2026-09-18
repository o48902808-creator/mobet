package ai.arena.mobet.agent

import ai.arena.mobet.security.EncryptedStateStore
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONObject

/** Encrypted crash checkpoint. Mobet never auto-resumes; consequential actions cannot be replayed. */
class RunCheckpointStore(context: Context) {
    private val store = EncryptedStateStore(context, "run_checkpoint_v1")

    fun start(goal: AgentGoal) = write("planning", goal.allowedPackage, 0, null, false)
    fun beforeAction(goal: AgentGoal, cycle: Int, action: AgentAction) =
        write("before_action", goal.allowedPackage, cycle, action.id, !action.reversible)
    fun afterAction(goal: AgentGoal, cycle: Int, action: AgentAction) =
        write("after_action", goal.allowedPackage, cycle, action.id, !action.reversible)
    fun finish() = store.clear()

    /** A leftover checkpoint is diagnostic only. Explicit user restart is always required. */
    fun interruptedSummary(): String? = store.read()?.let {
        runCatching {
            val root = JSONObject(it)
            "Interrupted run: ${root.optString("phase")} · cycle ${root.optInt("cycle")}" +
                if (root.optBoolean("irreversible")) " · irreversible action will not be replayed" else ""
        }.getOrNull()
    }

    private fun write(phase: String, packageName: String, cycle: Int, actionId: String?, irreversible: Boolean) {
        store.write(JSONObject().put("phase", phase).put("package", packageName).put("cycle", cycle)
            .put("action", actionId).put("irreversible", irreversible).put("at", System.currentTimeMillis()).toString())
    }
}

data class ResourceVerdict(val allowed: Boolean, val reason: String)

/** Battery and thermal limits prevent runaway autonomy under unsafe operating conditions. */
class ResourceGovernor(private val context: Context) {
    fun check(): ResourceVerdict {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if (percent in 0 until MIN_BATTERY_PERCENT) return ResourceVerdict(false, "battery below $MIN_BATTERY_PERCENT%")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val thermal = context.getSystemService(PowerManager::class.java)?.currentThermalStatus
                ?: PowerManager.THERMAL_STATUS_NONE
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return ResourceVerdict(false, "device thermal state is severe")
        }
        return ResourceVerdict(true, "resource budget available")
    }

    private companion object { const val MIN_BATTERY_PERCENT = 10 }
}
