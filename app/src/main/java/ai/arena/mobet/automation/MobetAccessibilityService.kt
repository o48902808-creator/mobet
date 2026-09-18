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
    override fun onInterrupt() { runner?.cancel("Interrupted") }

    override fun onDestroy() {
        runner?.cancel("Service stopped")
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun run(workflow: Workflow) {
        runner?.cancel("Replaced by a new run")
        runner = WorkflowRunner(this, ::emit).also { it.start(workflow) }
    }

    fun stopRun() = runner?.cancel("Stopped by user")

    fun startRecording() {
        runner?.cancel("Recording started")
        recorder.start()
        emit("Recording taps — switch to the target app")
    }

    fun stopRecording(): String {
        val result = recorder.stop()
        emit("Recording stopped")
        return result
    }

    fun launchTarget(packageName: String): Boolean = launch(packageName)

    fun requestConfirmation(message: String) {
        emit("Waiting for confirmation")
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_CONFIRM_MESSAGE, message)
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
                        if (match == null) callback(false, "OCR text not found: $query", 0.0, 0.0)
                        else callback(true, "Matched “${match.text}” at ${match.confidence}% confidence", x, y)
                    }
                }
                override fun onFailure(errorCode: Int) =
                    callback(false, "OCR screenshot error $errorCode", 0.0, 0.0)
            })
    }

    fun latestSnapshot(): ScreenSnapshot? = snapshot

    fun diagnosticHistory(): List<String> =
        getSharedPreferences("diagnostics", MODE_PRIVATE).getString("events", "")
            .orEmpty().lineSequence().filter(String::isNotBlank).toList()

    fun clearDiagnosticHistory() =
        getSharedPreferences("diagnostics", MODE_PRIVATE).edit().remove("events").apply()

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
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    companion object {
        const val ACTION_STATUS = "ai.arena.mobet.STATUS"
        const val ACTION_CONFIRM = "ai.arena.mobet.CONFIRM"
        const val EXTRA_STATUS = "status"
        const val EXTRA_CONFIRM_MESSAGE = "confirm_message"
        @Volatile var instance: MobetAccessibilityService? = null
            private set
    }
}
