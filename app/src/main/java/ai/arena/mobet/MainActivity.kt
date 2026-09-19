package ai.arena.mobet

import ai.arena.mobet.automation.MobetAccessibilityService
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.security.SecretStore
import ai.arena.mobet.ui.MobetUi
import ai.arena.mobet.ui.MobetUi.Row
import ai.arena.mobet.ui.MobetUi.Tone
import ai.arena.mobet.ui.MobetUi.dp
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mobet's single control surface.
 *
 * The screen is organised into task-oriented cards — service state, workflow editor, planning,
 * inspection tools, live activity — with the destructive/primary run controls pinned to a
 * bottom bar. Long-form output moves into [MobetUi.ReportSheet] bottom sheets instead of
 * cramped alert dialogs, and every transient message is a snackbar plus a persistent entry in
 * the activity log so nothing is lost when a toast disappears.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceState: TextView
    private lateinit var serviceDetail: TextView
    private lateinit var serviceDot: View
    private lateinit var serviceCard: MaterialCardView
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var workflowSummary: ChipGroup
    private lateinit var runProgress: CircularProgressIndicator

    /** Rolling in-memory log so the activity card shows history, not just the newest line. */
    private val activityLog = ArrayDeque<String>()
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(MobetAccessibilityService.EXTRA_STATUS)?.let { showStatus(it) }
            refreshServiceState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Workflow JSON, confirmation prompts, and secret metadata must not leak through recents
        // or third-party screenshots. Consented captures target the other app via Accessibility.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        wireActions()
        loadWorkflowSource()

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(MobetAccessibilityService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handleServiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServiceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        serviceCard = findViewById(R.id.serviceCard)
        serviceState = findViewById(R.id.serviceState)
        serviceDetail = findViewById(R.id.serviceDetail)
        serviceDot = findViewById(R.id.serviceDot)
        status = findViewById(R.id.status)
        editor = findViewById(R.id.editor)
        workflowSummary = findViewById(R.id.workflowSummary)
        runProgress = findViewById(R.id.runProgress)

        status.movementMethod = ScrollingMovementMethod()
        status.text = getString(R.string.status_ready)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_help) {
                showHelp(); true
            } else false
        }
    }

    /** Keeps content clear of the status bar, gesture bar and the pinned run bar. */
    private fun applyWindowInsets() {
        val appBar = findViewById<View>(R.id.appBar)
        val runBar = findViewById<View>(R.id.runBar)
        val scroll = findViewById<View>(R.id.contentScroll)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootCoordinator)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.updatePadding(top = bars.top)
            runBar.updatePadding(bottom = bars.bottom + dp(4))
            runBar.post {
                scroll.updatePadding(bottom = runBar.height + dp(16))
            }
            insets
        }
    }

    private fun wireActions() {
        findViewById<View>(R.id.openAccessibility).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { showStatus("Could not open Accessibility settings", Tone.DANGER) }
        }
        findViewById<View>(R.id.restrictedHelp).setOnClickListener { showRestrictedSettingsHelp() }
        findViewById<View>(R.id.formatJson).setOnClickListener { formatWorkflowJson() }
        findViewById<View>(R.id.expandEditor).setOnClickListener { showEditorFullScreen() }
        findViewById<View>(R.id.saveWorkflow).setOnClickListener { saveToLibrary() }
        findViewById<View>(R.id.loadWorkflow).setOnClickListener { loadFromLibrary() }
        findViewById<View>(R.id.manageSecrets).setOnClickListener { manageSecrets() }
        findViewById<View>(R.id.recordTaps).setOnClickListener { startRecorder() }
        findViewById<View>(R.id.generatePlan).setOnClickListener { showGoalPlanner() }
        findViewById<View>(R.id.runGoal).setOnClickListener { showAutonomousGoal() }
        findViewById<View>(R.id.dryRun).setOnClickListener { dryRunPlan() }
        findViewById<View>(R.id.inspectScreen).setOnClickListener { showInspector() }
        findViewById<View>(R.id.showDiagnostics).setOnClickListener { showDiagnostics() }
        findViewById<View>(R.id.showCaptures).setOnClickListener { showLatestCapture() }
        findViewById<View>(R.id.showAudit).setOnClickListener { showAuditLedger() }
        findViewById<View>(R.id.showMemory).setOnClickListener { showAgentMemory() }
        findViewById<View>(R.id.validatePolicy).setOnClickListener { validatePlan() }
        findViewById<View>(R.id.runWorkflow).setOnClickListener { runWorkflow() }
        findViewById<View>(R.id.stopRun).setOnClickListener { stopAndImport() }

        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshWorkflowSummary()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun loadWorkflowSource() {
        val sample = assets.open("sample_workflow.json").bufferedReader().use { it.readText() }
        val saved = getPreferences(MODE_PRIVATE).getString("workflow", sample) ?: sample
        editor.setText(saved)
        refreshWorkflowSummary()
    }

    // ── Live workflow summary chips ──────────────────────────────────────────

    /**
     * Parses the editor content on every keystroke and surfaces the target package, step count
     * and policy posture as chips. Invalid JSON shows a single error chip instead of failing
     * silently at run time.
     */
    private fun refreshWorkflowSummary() {
        workflowSummary.removeAllViews()
        val source = editor.text?.toString().orEmpty()
        if (source.isBlank()) return
        val workflow = runCatching { Workflow.parse(source) }.getOrNull()
        if (workflow == null) {
            addChip("Invalid JSON", Tone.DANGER, R.drawable.ic_warning)
            return
        }
        workflow.packageName?.let { addChip(it.substringAfterLast('.'), Tone.NEUTRAL) }
        addChip("${workflow.steps.size}/${workflow.policy.maxActions} steps", Tone.NEUTRAL)
        addChip("${workflow.policy.maxRuntimeMs / 1000}s budget", Tone.NEUTRAL)
        if (workflow.policy.allowVisualFallbacks) addChip("Visual fallback", Tone.WARNING)
        if (workflow.policy.allowSelfHealing) addChip("Self-healing", Tone.NEUTRAL)
        val violations = runCatching { PlanValidator.validate(workflow) }.getOrDefault(emptyList())
        if (violations.isEmpty()) addChip("Policy OK", Tone.SUCCESS, R.drawable.ic_check)
        else addChip("${violations.size} policy issue${if (violations.size == 1) "" else "s"}",
            Tone.DANGER, R.drawable.ic_warning)
    }

    private fun addChip(label: String, tone: Tone, icon: Int? = null) {
        val chip = Chip(this).apply {
            text = label
            isClickable = false
            isCheckable = false
            chipMinHeight = dp(28).toFloat()
            setEnsureMinTouchTargetSize(false)
            textSize = 11f
            val color = when (tone) {
                Tone.SUCCESS -> ContextCompat.getColor(this@MainActivity, R.color.mobet_success)
                Tone.WARNING -> ContextCompat.getColor(this@MainActivity, R.color.mobet_warning)
                Tone.DANGER -> ContextCompat.getColor(this@MainActivity, R.color.mobet_danger)
                Tone.NEUTRAL -> ContextCompat.getColor(this@MainActivity, R.color.mobet_on_surface_variant)
            }
            setTextColor(color)
            chipStrokeWidth = dp(1).toFloat()
            chipStrokeColor = android.content.res.ColorStateList.valueOf(
                (color and 0x00FFFFFF) or 0x55000000
            )
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                (color and 0x00FFFFFF) or 0x14000000
            )
            icon?.let {
                chipIcon = ContextCompat.getDrawable(this@MainActivity, it)
                chipIconTint = android.content.res.ColorStateList.valueOf(color)
                chipIconSize = dp(14).toFloat()
            }
        }
        workflowSummary.addView(chip)
    }

    // ── Workflow editor helpers ──────────────────────────────────────────────

    private fun formatWorkflowJson() {
        try {
            val pretty = JSONObject(editor.text.toString()).toString(2)
            editor.setText(pretty)
            showStatus("Workflow JSON reformatted", Tone.SUCCESS)
        } catch (error: Exception) {
            showStatus("Could not format: ${error.message}", Tone.DANGER)
        }
    }

    /** Distraction-free editing for long workflows that don't fit the inline card. */
    private fun showEditorFullScreen() {
        val field = MobetUi.Field(this, getString(R.string.workflow_json), lines = 16)
        field.input.setText(editor.text)
        field.input.typeface = android.graphics.Typeface.MONOSPACE
        field.input.textSize = 13f
        MobetUi.dialog(this)
            .setTitle(R.string.workflow_json)
            .setView(MobetUi.formContainer(this, field.layout))
            .setPositiveButton("Apply") { _, _ ->
                editor.setText(field.input.text)
                showStatus("Workflow updated", Tone.SUCCESS)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── Inspection ───────────────────────────────────────────────────────────

    private fun showInspector() {
        val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
        if (snapshot == null) {
            showStatus("Use another app first, then return to inspect its last captured screen", Tone.WARNING)
            return
        }
        if (snapshot.elements.isEmpty()) {
            showStatus("No selectable elements were exposed by ${snapshot.packageName}", Tone.WARNING)
            return
        }
        val rows = snapshot.elements.map { element ->
            Row(
                title = element.label.ifBlank { "(unlabelled)" },
                subtitle = buildString {
                    append(element.role)
                    if (element.matches > 1) append(" · ⚠ ${element.matches} matches")
                },
                badge = "${element.confidence}%",
                badgeColor = confidenceColor(element.confidence),
                icon = R.drawable.ic_inspect
            )
        }
        MobetUi.picker(
            activity = this,
            title = "Screen elements",
            subtitle = "${snapshot.packageName} · ${snapshot.elements.size} actionable",
            icon = R.drawable.ic_inspect,
            rows = rows
        ) { index -> showElementDetail(snapshot.elements[index]) }
    }

    private fun showElementDetail(item: ai.arena.mobet.automation.InspectedElement) {
        MobetUi.ReportSheet(this)
            .title(item.label.ifBlank { "(unlabelled)" }, R.drawable.ic_inspect)
            .subtitle("${item.role} · ${item.confidence}% confidence")
            .apply {
                if (item.matches > 1) {
                    banner("⚠ ${item.matches} elements share this selector — it may be ambiguous", Tone.WARNING)
                }
            }
            .monospace(item.selector)
            .paragraph("Matches: ${item.matches}\nRole: ${item.role}\nBounds: ${item.bounds}")
            .action(getString(R.string.action_copy_selector), primary = true) {
                copyToClipboard("Mobet selector", item.selector)
                showStatus("Selector copied to clipboard", Tone.SUCCESS)
            }
            .action(getString(R.string.action_close))
            .show()
    }

    private fun confidenceColor(confidence: Int): Int = ContextCompat.getColor(
        this,
        when {
            confidence >= 75 -> R.color.mobet_success
            confidence >= 45 -> R.color.mobet_warning
            else -> R.color.mobet_danger
        }
    )

    // ── Captures ─────────────────────────────────────────────────────────────

    private fun showLatestCapture() {
        val file = File(filesDir, "captures").listFiles()
            ?.filter { it.extension.equals("png", true) }
            ?.maxByOrNull { it.lastModified() }
        if (file == null) {
            MobetUi.ReportSheet(this)
                .title("Captures", R.drawable.ic_captures)
                .empty(
                    "No screenshots captured",
                    "Consent-gated captures appear here after a workflow uses a visual action.",
                    R.drawable.ic_captures
                )
                .show()
            return
        }
        val image = ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            adjustViewBounds = true
            contentDescription = "Latest consented Mobet screenshot"
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_status_surface)
        }
        MobetUi.ReportSheet(this)
            .title("Latest capture", R.drawable.ic_captures)
            .subtitle("${file.name} · stored in private app storage")
            .custom(image)
            .action("Read text", primary = true) { recognizeCapture(file) }
            .action(getString(R.string.action_delete), destructive = true) {
                confirmDestructive(
                    "Delete this screenshot?",
                    "The capture is removed from private app storage immediately.",
                    getString(R.string.action_delete)
                ) {
                    file.delete()
                    showStatus("Screenshot deleted", Tone.SUCCESS)
                }
            }
            .action(getString(R.string.action_close))
            .show()
    }

    private fun recognizeCapture(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            showStatus("Could not decode screenshot", Tone.DANGER)
            return
        }
        showBusy(true)
        showStatus("Running on-device OCR…")
        ai.arena.mobet.vision.OnDeviceTextRecognizer.recognize(bitmap) { result ->
            bitmap.recycle()
            showBusy(false)
            result.onSuccess { lines ->
                val sheet = MobetUi.ReportSheet(this)
                    .title("Recognized text", R.drawable.ic_captures)
                    .subtitle("${lines.size} lines · recognized entirely on-device")
                if (lines.isEmpty()) {
                    sheet.empty("No text recognized", "The capture may be blank or non-Latin script.")
                } else {
                    sheet.monospace(lines.joinToString("\n") { "${it.confidence}%  ${it.text}" })
                }
                sheet.show()
                showStatus("OCR completed on-device", Tone.SUCCESS)
            }.onFailure { showStatus("OCR failed: ${it.message}", Tone.DANGER) }
        }
    }

    // ── Diagnostics, ledger, memory ──────────────────────────────────────────

    private fun showDiagnostics() {
        val service = MobetAccessibilityService.instance
        val history = service?.diagnosticHistory().orEmpty()
        val sheet = MobetUi.ReportSheet(this)
            .title("Execution diagnostics", R.drawable.ic_diagnostics)
            .subtitle(if (history.isEmpty()) null else "${history.size} recent events")
        if (history.isEmpty()) {
            sheet.empty(
                "No events recorded yet",
                "Run a workflow and each step will be logged here.",
                R.drawable.ic_diagnostics
            )
        } else {
            sheet.monospace(history.joinToString("\n"))
            sheet.action(getString(R.string.action_clear), destructive = true) {
                service?.clearDiagnosticHistory()
                showStatus("Diagnostics cleared", Tone.SUCCESS)
            }
        }
        sheet.action(getString(R.string.action_close)).show()
    }

    private fun showAuditLedger() {
        val service = MobetAccessibilityService.instance ?: run {
            requireService(); return
        }
        val ledger = service.auditLedger()
        val verification = ledger.verify()
        val entries = ledger.entries()
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        val sheet = MobetUi.ReportSheet(this)
            .title("Audit ledger", R.drawable.ic_ledger)
            .subtitle("Tamper-evident SHA-256 hash chain")

        if (entries.isEmpty()) {
            sheet.empty(
                "No ledger entries yet",
                "Every runner event is appended here as a hash-chained record.",
                R.drawable.ic_ledger
            )
        } else {
            if (verification == null) {
                sheet.banner("✔ Hash chain verified — ${entries.size} entries intact", Tone.SUCCESS)
            } else {
                sheet.banner("✖ Integrity failure: $verification", Tone.DANGER)
            }
            sheet.monospace(entries.takeLast(60).joinToString("\n") {
                "#${it.sequence} ${format.format(Date(it.timestamp))}  ${it.event}\n    ⛓ ${it.hash.take(16)}…"
            })
            sheet.action(getString(R.string.action_clear), destructive = true) {
                confirmDestructive(
                    "Clear the audit ledger?",
                    "The tamper-evident history of every run will be permanently removed.",
                    getString(R.string.action_clear)
                ) {
                    ledger.clear()
                    showStatus("Audit ledger cleared", Tone.SUCCESS)
                }
            }
        }
        sheet.action(getString(R.string.action_close)).show()
    }

    private fun showAgentMemory() {
        val service = MobetAccessibilityService.instance ?: run {
            requireService(); return
        }
        MobetUi.ReportSheet(this)
            .title("Agent memory", R.drawable.ic_memory)
            .subtitle("AES-GCM encrypted, on-device only")
            .monospace(
                buildString {
                    append(service.agentMemorySummary())
                    append("\n")
                    append(service.worldModelSummary())
                    service.interruptedRunSummary()?.let { append("\n").append(it) }
                }
            )
            .paragraph(
                "Stored: structural hashes, bounded transition outcomes, confidence, recency, app " +
                    "versions, selector-repair hashes and dead ends. Excluded: screen text, OCR " +
                    "output, entered values and screenshots. Knowledge decays over time; repeated " +
                    "contradictions and major app-version changes invalidate it."
            )
            .action("Clear all", destructive = true) {
                confirmDestructive(
                    "Clear agent memory?",
                    "Learned routes and the screen-transition graph are erased. The agent will " +
                        "have to rediscover every path.",
                    "Clear all"
                ) {
                    service.clearAgentMemory()
                    service.clearWorldModel()
                    showStatus("Agent memory cleared", Tone.SUCCESS)
                }
            }
            .action(getString(R.string.action_close))
            .show()
    }

    // ── Confirmations from the accessibility service ─────────────────────────

    private fun handleServiceIntent(value: Intent?) {
        if (value?.action == ACTION_STOP_AUTONOMY) {
            intent.action = null
            MobetAccessibilityService.instance?.stopRun()
            showBusy(false)
            showStatus("Autonomous run stopped from notification", Tone.WARNING)
            return
        }
        handleConfirmation(value)
    }

    private fun handleConfirmation(value: Intent?) {
        if (value?.action != MobetAccessibilityService.ACTION_CONFIRM) return
        intent.action = null
        val message = value.getStringExtra(MobetAccessibilityService.EXTRA_CONFIRM_MESSAGE)
            ?: "Allow the next workflow action?"
        val hardened = value.getBooleanExtra(MobetAccessibilityService.EXTRA_CONFIRM_HARDENED, false)

        if (!hardened) {
            MobetUi.dialog(this)
                .setTitle("Workflow confirmation")
                .setIcon(R.drawable.ic_info)
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
        val field = MobetUi.plainInput(this, "Type APPROVE to allow")
        val dialog = MobetUi.dialog(this)
            .setTitle("⚠ Critical action confirmation")
            .setIcon(R.drawable.ic_warning)
            .setMessage("$message\n\nThis step was scored CRITICAL risk. Type APPROVE to continue.")
            .setCancelable(false)
            .setView(MobetUi.formContainer(this, field))
            .setPositiveButton("Confirm") { _, _ ->
                val approved = field.text.toString().trim() == "APPROVE"
                if (!approved) {
                    showStatus("Typed confirmation did not match APPROVE — action denied", Tone.DANGER)
                }
                MobetAccessibilityService.instance?.respondToConfirmation(approved)
            }
            .setNegativeButton("Deny") { _, _ ->
                MobetAccessibilityService.instance?.respondToConfirmation(false)
            }
            .create()
        dialog.show()

        // Keep the irreversible action un-tappable until the exact word has been typed.
        val confirm = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        confirm.isEnabled = false
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                confirm.isEnabled = s?.toString()?.trim() == "APPROVE"
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    /** Shared two-step gate for anything that erases user data. */
    private fun confirmDestructive(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        MobetUi.dialog(this)
            .setTitle(title)
            .setIcon(R.drawable.ic_warning)
            .setMessage(message)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── Secrets ──────────────────────────────────────────────────────────────

    private fun manageSecrets() {
        val store = SecretStore(this)
        val names = store.names()
        val rows = buildList {
            add(Row("Add or replace a secret", "Encrypted with an Android Keystore key",
                R.drawable.ic_secret))
            names.forEach { add(Row(it, "Tap to delete", R.drawable.ic_delete)) }
        }
        MobetUi.picker(
            activity = this,
            title = "Encrypted secrets",
            subtitle = if (names.isEmpty()) "No secrets stored yet" else "${names.size} stored",
            icon = R.drawable.ic_secret,
            rows = rows
        ) { index ->
            if (index == 0) showSecretEditor(store)
            else {
                val name = names[index - 1]
                confirmDestructive(
                    "Delete secret?",
                    "“$name” will be permanently removed from encrypted storage.",
                    getString(R.string.action_delete)
                ) {
                    store.delete(name)
                    showStatus("Secret “$name” deleted", Tone.SUCCESS)
                }
            }
        }
    }

    private fun showSecretEditor(store: SecretStore) {
        val name = MobetUi.Field(this, "Name", "Referenced as {{secret:name}} in a workflow")
        val value = MobetUi.Field(this, "Secret value", password = true)
        MobetUi.dialog(this)
            .setTitle("Store encrypted secret")
            .setIcon(R.drawable.ic_secret)
            .setView(MobetUi.formContainer(this, name.layout, value.layout))
            .setPositiveButton("Save") { _, _ ->
                try {
                    require(value.input.text?.isNotEmpty() == true) { "Secret value is empty" }
                    require(name.value.isNotBlank()) { "Secret name is required" }
                    store.put(name.value, value.input.text.toString())
                    showStatus("Encrypted secret saved", Tone.SUCCESS)
                } catch (error: Exception) {
                    showStatus("Could not save secret: ${error.message}", Tone.DANGER)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── Planning and autonomy ────────────────────────────────────────────────

    private fun showAutonomousGoal() {
        val notificationPrefs = getSharedPreferences("privacy_choices", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !notificationPrefs.getBoolean("notification_prompted", false)
        ) {
            notificationPrefs.edit().putBoolean("notification_prompted", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
            showStatus(
                "Notification permission requested for the emergency Stop control — tap Run goal again",
                Tone.WARNING
            )
            return
        }
        val service = MobetAccessibilityService.instance
        val snapshot = service?.latestSnapshot()
        if (service == null || snapshot == null || snapshot.packageName == packageName) {
            showStatus("Visit the target app first, then return to start a grounded goal", Tone.WARNING)
            return
        }

        val goal = MobetUi.Field(this, "Goal", "e.g. open Network settings", lines = 2)
        val evidence = MobetUi.Field(this, "Completion evidence", "Exact on-screen text, e.g. Internet")
        val ocr = MobetUi.checkBox(this, "Consent to on-device OCR for completion evidence")
        val model = MobetUi.checkBox(this, "Use structured on-device candidate ranking")

        MobetUi.dialog(this)
            .setTitle("Bounded autonomous run")
            .setIcon(R.drawable.ic_agent)
            .setMessage(
                "Target: ${snapshot.packageName}\n\n" +
                    "The agent may tap, scroll or go back within this app for at most 20 cycles. " +
                    "RiskEngine, WorkflowRunner, confirmations, package limits and live verification " +
                    "stay authoritative — it abstains when confidence is insufficient."
            )
            .setView(MobetUi.formContainer(this, goal.layout, evidence.layout, ocr, model))
            .setPositiveButton("Start run") { _, _ ->
                if (goal.value.isBlank() || evidence.value.isBlank()) {
                    showStatus("Goal and exact completion evidence are required", Tone.DANGER)
                } else {
                    showBusy(true)
                    service.startAutonomous(
                        ai.arena.mobet.agent.AgentGoal(
                            goal.value, evidence.value, snapshot.packageName,
                            maxCycles = 20, maxRisk = 29, minConfidence = 0.67,
                            lookaheadExpansions = 32,
                            allowOcrEvidence = ocr.isChecked,
                            allowModelAssistance = model.isChecked
                        )
                    )
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showGoalPlanner() {
        val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
        if (snapshot == null || snapshot.elements.isEmpty()) {
            showStatus("Visit the target app first so the goal can be grounded in its screen", Tone.WARNING)
            return
        }
        val input = MobetUi.Field(
            this,
            "Goal",
            "Example: tap “Network & internet” then wait for “Internet”",
            lines = 3
        )
        MobetUi.dialog(this)
            .setTitle("Generate grounded plan")
            .setIcon(R.drawable.ic_plan)
            .setMessage(
                "Target: ${snapshot.packageName}\n" +
                    "Only elements verified on the captured screen can be planned."
            )
            .setView(MobetUi.formContainer(this, input.layout))
            .setPositiveButton("Generate") { _, _ ->
                ai.arena.mobet.planner.GoalPlanner.generate(input.value, snapshot)
                    .onSuccess { plan ->
                        editor.setText(plan)
                        showStatus("Plan generated and policy-validated — review before running", Tone.SUCCESS)
                    }
                    .onFailure { showStatus("Planner rejected goal: ${it.message}", Tone.DANGER) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun dryRunPlan() {
        try {
            val workflow = Workflow.parse(editor.text.toString())
            val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
            val report = ai.arena.mobet.planner.PlanSimulator.simulate(workflow, snapshot)
            MobetUi.ReportSheet(this)
                .title("Dry run", R.drawable.ic_dryrun)
                .subtitle("Simulated against the last snapshot — the device is not touched")
                .monospace(report)
                .action(getString(R.string.action_close))
                .show()
        } catch (error: Exception) {
            showStatus("Invalid workflow: ${error.message}", Tone.DANGER)
        }
    }

    private fun validatePlan() {
        try {
            val workflow = Workflow.parse(editor.text.toString())
            val violations = PlanValidator.validate(workflow)
            val sheet = MobetUi.ReportSheet(this).title("Policy validation", R.drawable.ic_policy)
            if (violations.isEmpty()) {
                sheet.banner("✔ Approved by policy", Tone.SUCCESS)
                    .monospace(
                        "Target      ${workflow.packageName}\n" +
                            "Actions     ${workflow.steps.size} / ${workflow.policy.maxActions}\n" +
                            "Runtime     ${workflow.policy.maxRuntimeMs} ms\n" +
                            "Visual      ${if (workflow.policy.allowVisualFallbacks) "allowed" else "blocked"}\n" +
                            "Self-heal   ${if (workflow.policy.allowSelfHealing) "allowed" else "blocked"}"
                    )
            } else {
                sheet.banner("✖ Rejected — ${violations.size} violation${if (violations.size == 1) "" else "s"}", Tone.DANGER)
                    .rows(violations.map { violation ->
                        Row(
                            title = violation.message,
                            subtitle = violation.step?.let { "Step $it" } ?: "Plan level",
                            icon = R.drawable.ic_warning,
                            showChevron = false
                        )
                    })
            }
            sheet.action(getString(R.string.action_close)).show()
        } catch (error: Exception) {
            showStatus("Invalid workflow: ${error.message}", Tone.DANGER)
        }
    }

    // ── Library ──────────────────────────────────────────────────────────────

    private fun saveToLibrary() {
        val input = MobetUi.Field(this, "Workflow name")
        MobetUi.dialog(this)
            .setTitle("Save to library")
            .setIcon(R.drawable.ic_save)
            .setView(MobetUi.formContainer(this, input.layout))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.value
                if (name.isBlank()) {
                    showStatus("A workflow name is required", Tone.WARNING)
                    return@setPositiveButton
                }
                try {
                    Workflow.parse(editor.text.toString())
                    getSharedPreferences("library", MODE_PRIVATE).edit()
                        .putString(name, editor.text.toString()).apply()
                    showStatus("Saved “$name” to the library", Tone.SUCCESS)
                } catch (error: Exception) {
                    showStatus("Invalid workflow: ${error.message}", Tone.DANGER)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadFromLibrary() {
        val library = getSharedPreferences("library", MODE_PRIVATE)
        val names = library.all.keys.sorted()
        val rows = names.map { name ->
            val steps = runCatching {
                Workflow.parse(library.getString(name, "") ?: "").steps.size
            }.getOrNull()
            Row(
                title = name,
                subtitle = steps?.let { "$it step${if (it == 1) "" else "s"}" } ?: "Unparseable",
                icon = R.drawable.ic_library
            )
        }
        MobetUi.picker(
            activity = this,
            title = "Workflow library",
            subtitle = if (names.isEmpty()) null else "${names.size} saved",
            icon = R.drawable.ic_library,
            rows = rows,
            emptyTitle = "The library is empty",
            emptyBody = "Save the workflow you are editing to keep it here."
        ) { index ->
            library.getString(names[index], null)?.let(editor::setText)
            showStatus("Loaded “${names[index]}”", Tone.SUCCESS)
        }
    }

    // ── Recording ────────────────────────────────────────────────────────────

    private fun startRecorder() {
        val service = MobetAccessibilityService.instance ?: run { requireService(); return }
        val apps = packageManager.getInstalledApplications(0)
            .mapNotNull { app ->
                packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    Triple(packageManager.getApplicationLabel(app).toString(), app.packageName, it)
                }
            }
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            showStatus("No launchable apps found", Tone.WARNING)
            return
        }
        MobetUi.picker(
            activity = this,
            title = "Record taps in app",
            subtitle = "Mobet captures the selectors you touch, then you import them as steps",
            icon = R.drawable.ic_record,
            rows = apps.map { Row(it.first, it.second, R.drawable.ic_record) }
        ) { index ->
            val target = apps[index]
            service.startRecording()
            if (!service.launchTarget(target.second)) {
                showStatus("Could not launch ${target.first}", Tone.DANGER)
            } else {
                showStatus("Recording in ${target.first} — return and tap Stop to import", Tone.SUCCESS)
            }
        }
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
            showStatus("Imported ${recorded.length()} recorded steps", Tone.SUCCESS)
        } catch (error: Exception) {
            showStatus("Could not import recording: ${error.message}", Tone.DANGER)
        }
    }

    // ── Run controls ─────────────────────────────────────────────────────────

    private fun runWorkflow() {
        val service = MobetAccessibilityService.instance ?: run { requireService(); return }
        try {
            val source = editor.text.toString()
            val workflow = Workflow.parse(source)
            getPreferences(MODE_PRIVATE).edit().putString("workflow", source).apply()
            showBusy(true)
            service.run(workflow)
        } catch (error: Exception) {
            showBusy(false)
            showStatus("Invalid workflow: ${error.message}", Tone.DANGER)
        }
    }

    private fun stopAndImport() {
        val service = MobetAccessibilityService.instance
        showBusy(false)
        if (service == null) {
            requireService()
            return
        }
        service.stopRun()
        importRecordedSteps(service.stopRecording())
    }

    /** Nudges the user to the one setting that unblocks everything else. */
    private fun requireService() {
        MobetUi.snack(
            this,
            "Enable Mobet in Accessibility settings first",
            Tone.WARNING,
            "Open"
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        appendLog("Enable Mobet in Accessibility settings first")
    }

    // ── State rendering ──────────────────────────────────────────────────────

    private fun refreshServiceState() {
        val enabled = MobetAccessibilityService.instance != null || isServiceEnabled()
        serviceState.setText(if (enabled) R.string.service_enabled else R.string.service_disabled)
        serviceDetail.setText(
            if (enabled) R.string.service_enabled_detail else R.string.service_disabled_detail
        )
        val color = ContextCompat.getColor(
            this,
            if (enabled) R.color.mobet_success else R.color.mobet_danger
        )
        serviceDot.background?.mutate()?.let { DrawableCompat.setTint(it, color) }
        serviceState.setTextColor(color)
        serviceCard.strokeColor = (color and 0x00FFFFFF) or 0x55000000
        // Both the shortcut and the restricted-settings explainer are only useful while the
        // service is still off.
        val setupVisibility = if (enabled) View.GONE else View.VISIBLE
        findViewById<View>(R.id.openAccessibility).visibility = setupVisibility
        findViewById<View>(R.id.restrictedHelp).visibility = setupVisibility
        findViewById<View>(R.id.runWorkflow).isEnabled = enabled
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, MobetAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }

    private fun showBusy(busy: Boolean) {
        runProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /**
     * Surfaces a message twice: a snackbar for immediate attention and a timestamped line in
     * the activity card so the user can scroll back through what happened.
     */
    private fun showStatus(message: String, tone: Tone = Tone.NEUTRAL) {
        appendLog(message)
        if (tone != Tone.NEUTRAL) MobetUi.snack(this, message, tone)
        // Terminal words from the runner clear the busy indicator.
        if (TERMINAL_MARKERS.any { message.contains(it, ignoreCase = true) }) showBusy(false)
    }

    private fun appendLog(message: String) {
        activityLog.addLast("${logTime.format(Date())}  $message")
        while (activityLog.size > 80) activityLog.removeFirst()
        status.text = activityLog.joinToString("\n")
        status.post {
            val overflow = status.layout?.let { it.getLineTop(it.lineCount) - status.height } ?: 0
            if (overflow > 0) status.scrollTo(0, overflow)
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, value))
    }

    // ── Restricted settings (Android 13+) ────────────────────────────────────

    /**
     * Android 13 gates Accessibility behind "restricted settings" for apps installed outside a
     * store session, so a sideloaded Mobet shows a greyed-out toggle and a "Restricted setting"
     * dialog. The switch is not broken — the user has to allow restricted settings from the App
     * info page first. Surfacing that here saves a confusing detour through Android's UI.
     */
    private fun showRestrictedSettingsHelp() {
        MobetUi.ReportSheet(this)
            .title(getString(R.string.restricted_title), R.drawable.ic_warning)
            .subtitle(getString(R.string.restricted_subtitle))
            .paragraph(
                "Android blocks Accessibility access for apps installed from outside an app " +
                    "store — the toggle stays greyed out and tapping it shows “Restricted " +
                    "setting”. Nothing is wrong with Mobet; the permission has to be unlocked " +
                    "once from the App info page."
            )
            .rows(
                listOf(
                    Row("1 · Open Mobet's App info", "Use the button below, or Settings › Apps › Mobet", R.drawable.ic_info, showChevron = false),
                    Row("2 · Tap the ⋮ menu, top-right", "It is on the App info screen itself, not in Accessibility", R.drawable.ic_chevron_right, showChevron = false),
                    Row("3 · Tap “Allow restricted settings”", "Confirm with your PIN, pattern or biometric", R.drawable.ic_check, showChevron = false),
                    Row("4 · Return to Accessibility", "Mobet automation can now be switched on", R.drawable.ic_accessibility, showChevron = false)
                )
            )
            .paragraph(
                "No “Allow restricted settings” entry? Some OEM builds (Xiaomi, Samsung, Realme) " +
                    "move or gate it. Installing over adb with “adb install -r -g”, or running " +
                    "from Android Studio, is exempt from this restriction."
            )
            .action(getString(R.string.restricted_open_app_info), primary = true) { openAppInfo() }
            .action(getString(R.string.action_close))
            .show()
    }

    /** Deep-links to this app's own App info page, where restricted settings are unlocked. */
    private fun openAppInfo() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { showStatus("Could not open App info — use Settings › Apps › Mobet", Tone.DANGER) }
    }

    // ── Help ─────────────────────────────────────────────────────────────────

    private fun showHelp() {
        MobetUi.ReportSheet(this)
            .title("How Mobet works", R.drawable.ic_help)
            .subtitle("Everything runs on this device")
            .rows(
                listOf(
                    Row("1 · Enable the service", "Accessibility lets Mobet read labels and operate controls.", R.drawable.ic_accessibility, showChevron = false),
                    Row("2 · Write or record a workflow", "Edit JSON directly, or record taps inside a target app.", R.drawable.ic_record, showChevron = false),
                    Row("3 · Dry run and validate", "Grade each step against the last snapshot before touching the device.", R.drawable.ic_dryrun, showChevron = false),
                    Row("4 · Run it", "Consequential steps pause for confirmation; critical ones need a typed APPROVE.", R.drawable.ic_run, showChevron = false),
                    Row("5 · Verify", "The audit ledger hash-chains every event so tampering is detectable.", R.drawable.ic_ledger, showChevron = false)
                )
            )
            .paragraph(
                "Mobet has no network permission. Workflows, secrets, memory and captures never " +
                    "leave this device. Only automate apps and accounts you are authorized to use."
            )
            .action(getString(R.string.action_close))
            .show()
    }

    companion object {
        const val ACTION_STOP_AUTONOMY = "ai.arena.mobet.STOP_AUTONOMY"

        /** Runner phrases that mean no operation is in flight any more. */
        private val TERMINAL_MARKERS = listOf(
            "completed", "failed", "stopped", "aborted", "denied", "rejected", "cancelled"
        )
    }
}
