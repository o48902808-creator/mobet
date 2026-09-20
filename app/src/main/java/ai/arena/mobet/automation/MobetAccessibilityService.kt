package ai.arena.mobet.automation

import ai.arena.mobet.MainActivity
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MobetAccessibilityService : AccessibilityService() {
    private var runner: WorkflowRunner? = null
    private val recorder by lazy { InteractionRecorder(packageName) }
    private val ledger by lazy { ai.arena.mobet.audit.AuditLedger(this) }
    private val worldModel by lazy { ai.arena.mobet.agent.WorldModel(this) }
    private val agentMemory by lazy { ai.arena.mobet.agent.PersistentExperienceStore(this) }
    private val liveAgent by lazy { ai.arena.mobet.agent.LiveAndroidAgent(this, agentMemory, ::emit) }
    private var lastInspectionAt = 0L
    @Volatile private var snapshot: ScreenSnapshot? = null

    override fun onServiceConnected() {
        instance = this
        emit("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (recorder.observe(event)) emit("Recorded interaction")
        val eventPackage = event.packageName?.toString() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (eventPackage != packageName && now - lastInspectionAt >= 700) {
            rootInActiveWindow?.let { root ->
                try { snapshot = ScreenInspector.inspect(root, eventPackage) }
                finally { root.recycle() }
                lastInspectionAt = now
            }
        }
    }
    override fun onInterrupt() { liveAgent.cancel("Interrupted"); runner?.cancel("Interrupted") }

    override fun onDestroy() {
        liveAgent.cancel("Service stopped", quiet = true)
        runner?.cancel("Service stopped")
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun run(workflow: Workflow) {
        liveAgent.cancel("Autonomous run replaced by workflow", quiet = true)
        runner?.cancel("Replaced by a new run")
        runner = WorkflowRunner(this, ::emit).also { it.start(workflow) }
    }

    fun startAutonomous(goal: ai.arena.mobet.agent.AgentGoal) {
        runner?.cancel("Replaced by autonomous run")
        liveAgent.start(goal)
    }

    fun showAutonomyNotification() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        val channel = android.app.NotificationChannel(
            AUTONOMY_CHANNEL, "Active autonomous run", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Visible control for a Mobet run started by you" }
        manager.createNotificationChannel(channel)
        val stopIntent = android.app.PendingIntent.getActivity(
            this, 7,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_STOP_AUTONOMY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, AUTONOMY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Apex autonomous run active")
            .setContentText("Tap Stop to revoke device operation authority")
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent).build()
        runCatching { manager.notify(AUTONOMY_NOTIFICATION_ID, notification) }
    }

    fun hideAutonomyNotification() =
        getSystemService(android.app.NotificationManager::class.java).cancel(AUTONOMY_NOTIFICATION_ID)

    fun stopRun() {
        liveAgent.cancel("Stopped by user")
        runner?.cancel("Stopped by user")
    }

    internal fun stopGuardedExecution() = runner?.cancel("Guarded action stopped")

    fun startRecording() {
        liveAgent.cancel("Recording started", quiet = true)
        runner?.cancel("Recording started")
        recorder.start()
        emit("Recording taps — switch to the target app")
    }

    fun stopRecording(): String {
        val result = recorder.stop()
        emit("Recording stopped")
        return result
    }

    /** Whether a capture is in progress — lets the UI skip the import step on a plain Stop. */
    fun isRecording(): Boolean = recorder.active

    fun launchTarget(packageName: String): Boolean = launch(packageName)

    fun requestConfirmation(message: String, hardened: Boolean = false) {
        emit(if (hardened) "Waiting for typed confirmation" else "Waiting for confirmation")
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_CONFIRM_MESSAGE, message)
                .putExtra(EXTRA_CONFIRM_HARDENED, hardened)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    fun respondToConfirmation(approved: Boolean) {
        performGlobalAction(GLOBAL_ACTION_BACK)
        runner?.confirmationResult(approved)
    }

    fun activePackageName(): String? = rootInActiveWindow?.let { root ->
        try { root.packageName?.toString() } finally { root.recycle() }
    }

    fun performPointGesture(
        startX: Double, startY: Double, endX: Double?, endY: Double?, durationMs: Long,
        callback: (Boolean) -> Unit
    ) {
        val metrics = resources.displayMetrics
        fun x(value: Double) = (metrics.widthPixels * value.coerceIn(0.02, 0.98)).toFloat()
        fun y(value: Double) = (metrics.heightPixels * value.coerceIn(0.02, 0.98)).toFloat()
        val path = android.graphics.Path().apply {
            moveTo(x(startX), y(startY))
            if (endX != null && endY != null) lineTo(x(endX), y(endY))
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = callback(true)
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = callback(false)
        }, null)
    }

    fun captureScreen(callback: (Boolean, String) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            callback(false, "Screenshot capture requires Android 11+")
            return
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bitmap == null) {
                        callback(false, "Could not decode screenshot")
                        return
                    }
                    try {
                        val directory = java.io.File(filesDir, "captures").apply { mkdirs() }
                        val file = java.io.File(directory, "capture-${System.currentTimeMillis()}.png")
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        callback(true, file.absolutePath)
                    } catch (error: Exception) {
                        bitmap.recycle()
                        callback(false, error.message ?: "Screenshot save failed")
                    }
                }
                override fun onFailure(errorCode: Int) = callback(false, "Screenshot error $errorCode")
            })
    }

    fun findVisualText(query: String, callback: (Boolean, String, Double, Double) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            callback(false, "Visual OCR requires Android 11+", 0.0, 0.0)
            return
        }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bitmap == null) {
                        callback(false, "Could not decode screen for OCR", 0.0, 0.0)
                        return
                    }
                    ai.arena.mobet.vision.OnDeviceTextRecognizer.recognize(bitmap) { recognition ->
                        val items = recognition.getOrElse {
                            bitmap.recycle()
                            callback(false, it.message ?: "OCR failed", 0.0, 0.0)
                            return@recognize
                        }
                        val match = ai.arena.mobet.vision.OnDeviceTextRecognizer.bestMatch(items, query)
                        val x = match?.centerXPercent(bitmap.width) ?: 0.0
                        val y = match?.centerYPercent(bitmap.height) ?: 0.0
                        bitmap.recycle()
                        // Report the query, never the matched line. bestMatch returns whole OCR
                        // lines that *contain* the query, so echoing match.text would copy
                        // unrelated neighbouring screen text -- an account balance sharing a line
                        // with a "Transfer" button -- into the diagnostics log, the audit ledger
                        // and a broadcast Intent. The ledger promises it holds no screen content.
                        if (match == null) callback(false, "OCR text not found: $query", 0.0, 0.0)
                        else callback(true, "Matched “$query” at ${match.confidence}% confidence", x, y)
                    }
                }
                override fun onFailure(errorCode: Int) =
                    callback(false, "OCR screenshot error $errorCode", 0.0, 0.0)
            })
    }

    /** Consent is captured by the autonomous-run UI. OCR supplies evidence only, never actions. */
    fun verifyOcrEvidence(query: String, callback: (Boolean, Double) -> Unit) {
        findVisualText(query) { found, detail, _, _ ->
            val confidence = Regex("(\\d+)%").find(detail)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull()?.div(100.0) ?: if (found) 0.5 else 0.0
            callback(found, confidence.coerceIn(0.0, 1.0))
        }
    }

    fun latestSnapshot(): ScreenSnapshot? = snapshot

    /** Fresh snapshot for autonomous verification; node handles never cross this boundary. */
    fun currentSnapshot(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return snapshot
        return try {
            val pkg = root.packageName?.toString() ?: return snapshot
            ScreenInspector.inspect(root, pkg).also { if (pkg != packageName) snapshot = it }
        } finally { root.recycle() }
    }

    fun appVersion(targetPackage: String): String? = try {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(targetPackage, 0).versionName
    } catch (_: Exception) { null }

    /**
     * Non-bypassable action gateway used by the live agent. Risk is recomputed from the translated
     * step; AgentAction.risk is never trusted. WorkflowRunner remains the sole device executor.
     */
    fun runGuardedAgentAction(
        action: ai.arena.mobet.agent.AgentAction,
        goal: ai.arena.mobet.agent.AgentGoal,
        callback: (Boolean, String) -> Unit
    ) {
        val liveSnapshot = currentSnapshot()
        if (liveSnapshot?.packageName != goal.allowedPackage) {
            callback(false, "live package provenance check failed"); return
        }
        // Canonicalize against the current accessibility snapshot so neither a stale plan nor an
        // optional model can forge selectors, labels, confidence, or risk metadata.
        val canonical = if (action.kind == ai.arena.mobet.agent.AgentActionKind.BACK) action else {
            ai.arena.mobet.agent.AccessibilityObservationAdapter.adapt(
                liveSnapshot, appVersion(liveSnapshot.packageName)
            ).actions.firstOrNull { it.id == action.id && it.selector == action.selector }
        }
        if (canonical == null || canonical.trust == ai.arena.mobet.agent.ContentTrust.UNTRUSTED_INSTRUCTION) {
            callback(false, "action is stale, forged, or untrusted"); return
        }
        val step = ai.arena.mobet.agent.AccessibilityObservationAdapter.toStep(canonical)
        if (step == null) { callback(false, "unsupported or malformed agent action"); return }
        val assessment = ai.arena.mobet.policy.RiskEngine.assess(step)
        if (assessment.score > goal.maxRisk) { callback(false, "RiskEngine blocked score ${assessment.score}"); return }
        val steps = if (assessment.tier >= ai.arena.mobet.policy.RiskTier.ELEVATED) {
            listOf(Step("confirm", message = "Apex proposes: ${canonical.label}"), step)
        } else listOf(step)
        val policy = ai.arena.mobet.policy.AutomationPolicy(
            allowedPackages = setOf(goal.allowedPackage),
            allowedActions = steps.map { it.action }.toSet(),
            maxActions = steps.size,
            maxRuntimeMs = 30_000,
            allowVisualFallbacks = false,
            allowSelfHealing = false
        )
        val workflow = Workflow("Apex guarded action", goal.allowedPackage, emptyMap(), steps, policy)
        val violations = ai.arena.mobet.policy.PlanValidator.validate(workflow)
        if (violations.isNotEmpty()) { callback(false, "PlanValidator blocked: ${violations.joinToString { it.message }}"); return }
        runner?.cancel("Replaced by next guarded action")
        runner = WorkflowRunner(
            this, ::emit, callback, launchTarget = false, enforcePackageAtFirstStep = true
        ).also { it.start(workflow) }
    }

    fun agentMemorySummary(): String = agentMemory.summary()
    fun interruptedRunSummary(): String? = ai.arena.mobet.agent.RunCheckpointStore(this).interruptedSummary()
    fun clearAgentMemory() = agentMemory.clear()

    fun diagnosticHistory(): List<String> =
        getSharedPreferences("diagnostics", MODE_PRIVATE).getString("events", "")
            .orEmpty().lineSequence().filter(String::isNotBlank).toList()

    fun clearDiagnosticHistory() =
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().remove("events").apply()

    fun auditLedger(): ai.arena.mobet.audit.AuditLedger = ledger

    fun worldModelSummary(): String = worldModel.summary()

    fun clearWorldModel() = worldModel.clear()

    internal fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    internal fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    internal fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun emit(message: String) {
        val preferences = getSharedPreferences("diagnostics", MODE_PRIVATE)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val lines = (preferences.getString("events", "").orEmpty().lineSequence()
            .filter(String::isNotBlank).toList() + "$timestamp  $message").takeLast(100)
        preferences.edit().putString("events", lines.joinToString("\n")).apply()
        ledger.append(message)
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    companion object {
        const val ACTION_STATUS = "ai.arena.mobet.STATUS"
        const val ACTION_CONFIRM = "ai.arena.mobet.CONFIRM"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONFIRM_MESSAGE = "confirm_message"
        const val EXTRA_CONFIRM_HARDENED = "confirm_hardened"
        private const val AUTONOMY_CHANNEL = "apex-active-run"
        private const val AUTONOMY_NOTIFICATION_ID = 4890
        @Volatile var instance: MobetAccessibilityService? = null
            private set
    }
}
