package ai.arena.mobet

import ai.arena.mobet.automation.MobetAccessibilityService
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.security.SecretStore
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var serviceState: TextView
    private lateinit var status: TextView
    private lateinit var editor: EditText

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(MobetAccessibilityService.EXTRA_STATUS)?.let { showStatus(it) }
            refreshServiceState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Workflow JSON, confirmation prompts, and secret metadata must not leak through recents or
        // third-party screenshots. Consented captures target the other app via Accessibility.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        title = "Mobet"
        setContentView(buildUi())
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(MobetAccessibilityService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handleConfirmation(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConfirmation(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(17, 19, 24))
        }

        root.addView(TextView(this).apply {
            text = "Mobet"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "On-device workflow runner · you stay in control"
            textSize = 15f
            setTextColor(Color.rgb(190, 190, 200))
        }, margins(bottom = 20))

        serviceState = TextView(this).apply { textSize = 16f }
        root.addView(serviceState, margins(bottom = 8))
        root.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, margins(bottom = 18))

        root.addView(TextView(this).apply {
            text = "WORKFLOW JSON"
            textSize = 12f
            setTextColor(Color.rgb(190, 175, 240))
        })
        val sample = assets.open("sample_workflow.json").bufferedReader().use { it.readText() }
        val saved = getPreferences(MODE_PRIVATE).getString("workflow", sample) ?: sample
        editor = EditText(this).apply {
            setText(saved)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(34, 37, 45))
            setPadding(padding / 2, padding / 2, padding / 2, padding / 2)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            gravity = android.view.Gravity.TOP
            minLines = 14
            isHorizontalScrollBarEnabled = true
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, 6, 0, 12) })

        val library = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        library.addView(button("Save") { saveToLibrary() }, LinearLayout.LayoutParams(0, -2, 1f))
        library.addView(button("Load") { loadFromLibrary() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        library.addView(button("Secrets") { manageSecrets() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        root.addView(library, margins(bottom = 8))
        root.addView(button("Record taps and scrolls in an app") { startRecorder() }, margins(bottom = 8))
        val planning = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        planning.addView(button("Generate plan") { showGoalPlanner() }, LinearLayout.LayoutParams(0, -2, 1f))
        planning.addView(button("Run goal autonomously") { showAutonomousGoal() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        planning.addView(button("Dry run") { dryRunPlan() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        root.addView(planning, margins(bottom = 8))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(button("Inspect last app screen") { showInspector() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(button("Diagnostics") { showDiagnostics() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        tools.addView(button("Captures") { showLatestCapture() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        root.addView(tools, margins(bottom = 8))
        val trust = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trust.addView(button("Audit ledger") { showAuditLedger() }, LinearLayout.LayoutParams(0, -2, 1f))
        trust.addView(button("Agent memory") { showAgentMemory() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        trust.addView(button("Validate policy") { validatePlan() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        root.addView(trust, margins(bottom = 8))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Run") { runWorkflow() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Stop / import recording") {
            val service = MobetAccessibilityService.instance
            service?.stopRun()
            if (service != null) importRecordedSteps(service.stopRecording())
            else showStatus("Automation service is not connected")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 8 })
        root.addView(actions)

        status = TextView(this).apply {
            text = "Ready. Enable the service, then run the included Settings demo."
            textSize = 14f
            setTextColor(Color.rgb(205, 205, 215))
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(status, margins(top = 14, bottom = 8))
        root.addView(TextView(this).apply {
            text = "Only automate apps and accounts you are authorized to use. Review workflows before running them."
            textSize = 12f
            setTextColor(Color.rgb(245, 190, 105))
        })
        return root
    }

    private fun showInspector() {
        val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
        if (snapshot == null) {
            showStatus("Use another app first, then return to inspect its last captured screen")
            return
        }
        if (snapshot.elements.isEmpty()) {
            showStatus("No selectable elements were exposed by ${snapshot.packageName}")
            return
        }
        val labels = snapshot.elements.map {
            "${it.confidence}%  ${it.label.take(42)}  [${it.role}]" +
                if (it.matches > 1) "  ⚠ ${it.matches} matches" else ""
        }
        AlertDialog.Builder(this)
            .setTitle("${snapshot.packageName} · ${snapshot.elements.size} elements")
            .setItems(labels.toTypedArray()) { _, index ->
                val item = snapshot.elements[index]
                AlertDialog.Builder(this)
                    .setTitle(item.label)
                    .setMessage("Selector: ${item.selector}\nConfidence: ${item.confidence}%\nMatches: ${item.matches}\nRole: ${item.role}\nBounds: ${item.bounds}")
                    .setPositiveButton("Copy selector") { _, _ ->
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Mobet selector", item.selector))
                        showStatus("Selector copied")
                    }
                    .setNegativeButton("Close", null).show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showLatestCapture() {
        val file = java.io.File(filesDir, "captures").listFiles()
            ?.filter { it.extension.equals("png", true) }?.maxByOrNull { it.lastModified() }
        if (file == null) {
            showStatus("No screenshots captured")
            return
        }
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
            adjustViewBounds = true
            contentDescription = "Latest consented Mobet screenshot"
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(image)
            .setPositiveButton("Close", null)
            .setNeutralButton("Read text") { _, _ -> recognizeCapture(file) }
            .setNegativeButton("Delete") { _, _ ->
                file.delete()
                showStatus("Screenshot deleted")
            }.show()
    }

    private fun recognizeCapture(file: java.io.File) {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            showStatus("Could not decode screenshot")
            return
        }
        showStatus("Running on-device OCR…")
        ai.arena.mobet.vision.OnDeviceTextRecognizer.recognize(bitmap) { result ->
            bitmap.recycle()
            result.onSuccess { lines ->
                val text = lines.joinToString("\n") { "${it.confidence}%  ${it.text}" }
                AlertDialog.Builder(this)
                    .setTitle("Recognized text · ${lines.size} lines")
                    .setMessage(text.ifBlank { "No text recognized" })
                    .setPositiveButton("Close", null).show()
                showStatus("OCR completed on-device")
            }.onFailure { showStatus("OCR failed: ${it.message}") }
        }
    }

    private fun showDiagnostics() {
        val service = MobetAccessibilityService.instance
        val history = service?.diagnosticHistory().orEmpty()
        AlertDialog.Builder(this)
            .setTitle("Execution diagnostics")
            .setMessage(if (history.isEmpty()) "No events recorded yet" else history.joinToString("\n"))
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear") { _, _ ->
                service?.clearDiagnosticHistory()
                showStatus("Diagnostics cleared")
            }.show()
    }

    private fun showAuditLedger() {
        val service = MobetAccessibilityService.instance
        if (service == null) {
            showStatus("Enable Mobet in Accessibility settings first")
            return
        }
        val ledger = service.auditLedger()
        val verification = ledger.verify()
        val entries = ledger.entries()
        val header = if (verification == null)
            "✔ Hash chain verified — ${entries.size} entries intact"
        else "✖ INTEGRITY FAILURE: $verification"
        val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        val body = entries.takeLast(60).joinToString("\n") {
            "#${it.sequence} ${format.format(java.util.Date(it.timestamp))}  ${it.event}\n    ⛓ ${it.hash.take(16)}…"
        }
        AlertDialog.Builder(this)
            .setTitle("Tamper-evident audit ledger")
            .setMessage(if (entries.isEmpty()) "No ledger entries yet" else "$header\n\n$body")
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear") { _, _ ->
                ledger.clear()
                showStatus("Audit ledger cleared")
            }
            .show()
    }

    private fun showWorldModel() {
        val service = MobetAccessibilityService.instance
        if (service == null) {
            showStatus("Enable Mobet in Accessibility settings first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Learned screen-transition graph")
            .setMessage(
                service.worldModelSummary() +
                    "\n\nMobet passively learns which action moves each app from one screen to " +
                    "another while workflows run. Only structural fingerprint hashes and the " +
                    "selectors from your own workflows are stored — never captured screen " +
                    "content — and the graph stays on this device."
            )
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear") { _, _ ->
                service.clearWorldModel()
                showStatus("World model cleared")
            }
            .show()
    }

    private fun handleConfirmation(value: Intent?) {
        if (value?.action != MobetAccessibilityService.ACTION_CONFIRM) return
        intent.action = null
        val message = value.getStringExtra(MobetAccessibilityService.EXTRA_CONFIRM_MESSAGE)
            ?: "Allow the next workflow action?"
        val hardened = value.getBooleanExtra(MobetAccessibilityService.EXTRA_CONFIRM_HARDENED, false)
        if (!hardened) {
            AlertDialog.Builder(this)
                .setTitle("Workflow confirmation")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Approve") { _, _ ->
                    MobetAccessibilityService.instance?.respondToConfirmation(true)
                }
                .setNegativeButton("Deny") { _, _ ->
                    MobetAccessibilityService.instance?.respondToConfirmation(false)
                }
                .show()
            return
        }
        // Hardened path: the next step is CRITICAL risk (payment, deletion, transfer…).
        // The user must type APPROVE so a stray tap can never authorize it.
        val scale = resources.displayMetrics.density
        val field = EditText(this).apply { hint = "Type APPROVE to allow" }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * scale).toInt(), 0, (20 * scale).toInt(), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("⚠ Critical action confirmation")
            .setMessage("$message\n\nThis step was scored CRITICAL risk. Type APPROVE to continue.")
            .setCancelable(false)
            .setView(container)
            .setPositiveButton("Confirm") { _, _ ->
                val approved = field.text.toString().trim().equals("APPROVE", ignoreCase = false)
                if (!approved) showStatus("Typed confirmation did not match APPROVE — action denied")
                MobetAccessibilityService.instance?.respondToConfirmation(approved)
            }
            .setNegativeButton("Deny") { _, _ ->
                MobetAccessibilityService.instance?.respondToConfirmation(false)
            }
            .show()
    }

    private fun manageSecrets() {
        val store = SecretStore(this)
        val choices = listOf("＋ Add or replace secret") + store.names().map { "Delete: $it" }
        AlertDialog.Builder(this)
            .setTitle("Encrypted secrets")
            .setItems(choices.toTypedArray()) { _, index ->
                if (index == 0) showSecretEditor(store)
                else {
                    val name = store.names()[index - 1]
                    AlertDialog.Builder(this)
                        .setTitle("Delete secret?")
                        .setMessage(name)
                        .setPositiveButton("Delete") { _, _ -> store.delete(name) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSecretEditor(store: SecretStore) {
        val scale = resources.displayMetrics.density
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * scale).toInt(), 0, (20 * scale).toInt(), 0)
        }
        val name = EditText(this).apply { hint = "Name, e.g. account_password" }
        val value = EditText(this).apply {
            hint = "Secret value"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fields.addView(name); fields.addView(value)
        AlertDialog.Builder(this)
            .setTitle("Store encrypted secret")
            .setView(fields)
            .setPositiveButton("Save") { _, _ ->
                try {
                    require(value.text.isNotEmpty()) { "Secret value is empty" }
                    store.put(name.text.toString().trim(), value.text.toString())
                    showStatus("Encrypted secret saved")
                } catch (error: Exception) {
                    showStatus("Could not save secret: ${error.message}")
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAgentMemory() {
        val service = MobetAccessibilityService.instance
        if (service == null) { showStatus("Enable Mobet in Accessibility settings first"); return }
        AlertDialog.Builder(this)
            .setTitle("Private Apex memory")
            .setMessage(service.agentMemorySummary() + "\n" + service.worldModelSummary() +
                "\n\nStored locally: structural hashes, bounded transition outcomes, confidence, recency, app versions, selector-repair hashes, and dead ends. Screen text, OCR output, entered values, and screenshots are excluded. Knowledge decays and major app-version changes invalidate it.")
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear all") { _, _ ->
                service.clearAgentMemory(); service.clearWorldModel(); showStatus("Agent memory cleared")
            }.show()
    }

    private fun showAutonomousGoal() {
        val service = MobetAccessibilityService.instance
        val snapshot = service?.latestSnapshot()
        if (service == null || snapshot == null || snapshot.packageName == packageName) {
            showStatus("Visit the target app first, then return to start a grounded goal")
            return
        }
        val scale = resources.displayMetrics.density
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * scale).toInt(), 0, (20 * scale).toInt(), 0)
        }
        val goal = EditText(this).apply { hint = "Goal, e.g. open Network settings"; minLines = 2 }
        val evidence = EditText(this).apply { hint = "Exact completion evidence, e.g. Internet" }
        fields.addView(goal); fields.addView(evidence)
        AlertDialog.Builder(this)
            .setTitle("Apex autonomous run")
            .setMessage("Target: ${snapshot.packageName}\n\nApex may tap, scroll, or go back within this app for at most 20 cycles. RiskEngine, WorkflowRunner, confirmations, package limits, and live verification remain authoritative. It abstains when confidence is insufficient.")
            .setView(fields)
            .setPositiveButton("Start bounded run") { _, _ ->
                val description = goal.text.toString().trim()
                val success = evidence.text.toString().trim()
                if (description.isBlank() || success.isBlank()) {
                    showStatus("Goal and exact completion evidence are required")
                } else {
                    service.startAutonomous(ai.arena.mobet.agent.AgentGoal(
                        description, success, snapshot.packageName, maxCycles = 20,
                        maxRisk = 29, minConfidence = 0.67, lookaheadExpansions = 32
                    ))
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showGoalPlanner() {
        val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
        if (snapshot == null || snapshot.elements.isEmpty()) {
            showStatus("Visit the target app first so the goal can be grounded in its screen")
            return
        }
        val input = EditText(this).apply {
            hint = "Example: tap “Network & internet” then wait for “Internet”"
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Generate grounded plan")
            .setMessage("Target: ${snapshot.packageName}\nOnly elements verified on the captured screen can be planned.")
            .setView(input)
            .setPositiveButton("Generate") { _, _ ->
                ai.arena.mobet.planner.GoalPlanner.generate(input.text.toString(), snapshot)
                    .onSuccess { plan ->
                        editor.setText(plan)
                        showStatus("Generated and policy-validated plan — review before running")
                    }
                    .onFailure { showStatus("Planner rejected goal: ${it.message}") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun dryRunPlan() {
        try {
            val workflow = Workflow.parse(editor.text.toString())
            val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
            val report = ai.arena.mobet.planner.PlanSimulator.simulate(workflow, snapshot)
            val view = TextView(this).apply {
                text = report
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            val scroll = ScrollView(this).apply { addView(view) }
            AlertDialog.Builder(this)
                .setTitle("Counterfactual dry run")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show()
        } catch (error: Exception) {
            showStatus("Invalid workflow: ${error.message}")
        }
    }

    private fun validatePlan() {
        try {
            val workflow = Workflow.parse(editor.text.toString())
            val violations = PlanValidator.validate(workflow)
            val message = if (violations.isEmpty()) {
                "Approved by policy\n\nTarget: ${workflow.packageName}\nActions: ${workflow.steps.size}/${workflow.policy.maxActions}\nRuntime: ${workflow.policy.maxRuntimeMs} ms\nVisual fallback: ${workflow.policy.allowVisualFallbacks}"
            } else violations.joinToString("\n") {
                "• " + (it.step?.let { step -> "Step $step: " } ?: "") + it.message
            }
            AlertDialog.Builder(this)
                .setTitle(if (violations.isEmpty()) "Plan valid" else "Plan rejected")
                .setMessage(message).setPositiveButton("Close", null).show()
        } catch (error: Exception) {
            showStatus("Invalid workflow: ${error.message}")
        }
    }

    private fun saveToLibrary() {
        val input = EditText(this).apply { hint = "Workflow name" }
        AlertDialog.Builder(this)
            .setTitle("Save workflow")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                try {
                    Workflow.parse(editor.text.toString())
                    getSharedPreferences("library", MODE_PRIVATE).edit()
                        .putString(name, editor.text.toString()).apply()
                    showStatus("Saved “$name”")
                } catch (error: Exception) {
                    showStatus("Invalid workflow: ${error.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadFromLibrary() {
        val library = getSharedPreferences("library", MODE_PRIVATE)
        val names = library.all.keys.sorted()
        if (names.isEmpty()) {
            showStatus("The workflow library is empty")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Workflow library")
            .setItems(names.toTypedArray()) { _, index ->
                library.getString(names[index], null)?.let(editor::setText)
                showStatus("Loaded “${names[index]}”")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRecorder() {
        val service = MobetAccessibilityService.instance
        if (service == null) {
            showStatus("Enable Mobet in Accessibility settings first")
            return
        }
        val apps = packageManager.getInstalledApplications(0)
            .mapNotNull { app ->
                packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    Triple(packageManager.getApplicationLabel(app).toString(), app.packageName, it)
                }
            }
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            showStatus("No launchable apps found")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Record taps in app")
            .setItems(apps.map { it.first }.toTypedArray()) { _, index ->
                val target = apps[index]
                service.startRecording()
                if (!service.launchTarget(target.second)) showStatus("Could not launch ${target.first}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importRecordedSteps(source: String) {
        try {
            val recorded = JSONArray(source)
            if (recorded.length() == 0) {
                showStatus("Stopped. No recorded taps to import")
                return
            }
            val root = JSONObject(editor.text.toString())
            val steps = root.optJSONArray("steps") ?: JSONArray().also { root.put("steps", it) }
            for (i in 0 until recorded.length()) steps.put(recorded.getJSONObject(i))
            editor.setText(root.toString(2))
            showStatus("Imported ${recorded.length()} recorded tap steps")
        } catch (error: Exception) {
            showStatus("Could not import recording: ${error.message}")
        }
    }

    private fun runWorkflow() {
        val service = MobetAccessibilityService.instance
        if (service == null) {
            showStatus("Enable Mobet in Accessibility settings first")
            return
        }
        try {
            val source = editor.text.toString()
            val workflow = Workflow.parse(source)
            getPreferences(MODE_PRIVATE).edit().putString("workflow", source).apply()
            service.run(workflow)
        } catch (error: Exception) {
            showStatus("Invalid workflow: ${error.message}")
        }
    }

    private fun refreshServiceState() {
        val enabled = MobetAccessibilityService.instance != null || isServiceEnabled()
        serviceState.text = if (enabled) "● Automation service enabled" else "● Automation service disabled"
        serviceState.setTextColor(if (enabled) Color.rgb(110, 220, 150) else Color.rgb(245, 130, 120))
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, MobetAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }

    private fun showStatus(message: String) {
        status.text = message
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        isAllCaps = false
    }

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            val scale = resources.displayMetrics.density
            setMargins(0, (top * scale).toInt(), 0, (bottom * scale).toInt())
        }
}
