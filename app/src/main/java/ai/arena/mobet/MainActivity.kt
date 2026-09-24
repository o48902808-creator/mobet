package ai.arena.mobet

import ai.arena.mobet.automation.ExecutionTimelineEvent
import ai.arena.mobet.automation.MobetAccessibilityService
import ai.arena.mobet.automation.PresenceLauncher
import ai.arena.mobet.automation.RunReminder
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.automation.WorkflowDocument
import ai.arena.mobet.automation.WorkflowTransfer
import ai.arena.mobet.audit.AuditLedger
import ai.arena.mobet.audit.LedgerBuildIdentity
import ai.arena.mobet.audit.LedgerExport
import ai.arena.mobet.planner.IntentSource
import ai.arena.mobet.planner.IntentToPlanPipeline
import ai.arena.mobet.planner.RunMode
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.provenance.BuildIntegrity
import ai.arena.mobet.provenance.BuildIntegrityReport
import ai.arena.mobet.provenance.ProvenanceVerification
import ai.arena.mobet.provenance.SigstoreProvenance
import ai.arena.mobet.security.SecretStore
import ai.arena.mobet.synthesis.AgentCrystallizer
import ai.arena.mobet.synthesis.PlanQuality
import ai.arena.mobet.synthesis.SynthesizedWorkflow
import ai.arena.mobet.synthesis.TraceSynthesizer
import ai.arena.mobet.synthesis.WorkflowRecipes
import ai.arena.mobet.synthesis.WorkflowRepair
import ai.arena.mobet.synthesis.WorkflowSynthesizer
import ai.arena.mobet.ui.JsonErrorLocator
import ai.arena.mobet.ui.JsonHighlighter
import ai.arena.mobet.ui.MobetUi
import ai.arena.mobet.ui.MobetUi.Row
import ai.arena.mobet.ui.MobetUi.Tone
import ai.arena.mobet.ui.MobetUi.dp
import ai.arena.mobet.voice.AudioInput
import ai.arena.mobet.voice.VoiceEngines
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var workflowCard: MaterialCardView
    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var workflowSummary: ChipGroup
    private lateinit var runProgress: CircularProgressIndicator
    private lateinit var timelinePanel: View
    private lateinit var timelineState: TextView
    private lateinit var timelineGoal: TextView
    private lateinit var timelineStep: TextView
    private lateinit var timelineDetail: TextView

    /** Rolling in-memory log so the activity card shows history, not just the newest line. */
    private val activityLog = ArrayDeque<String>()
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var highlighter: JsonHighlighter
    private var highlighting = false

    /** Looping "armed" cue for the service dot; cancelled whenever the service drops. */
    private var dotBreath: android.animation.ObjectAnimator? = null

    /** The most recent error-flash span, removed by reference so the highlighter is untouched. */
    private var lastErrorFlash: android.text.style.BackgroundColorSpan? = null

    /**
     * Package of the app a recording was started in, kept so the import can write it into the
     * workflow. Without it the recorded steps would land in a document whose `package` (and
     * `policy.allowedPackages`) still points at whatever was there before, and the freshly
     * recorded workflow would be rejected until hand-edited.
     */
    private var recordingPackage: String? = null
    /** Last autonomous run already offered for crystallization; prevents repeat prompts. */
    private var crystallizedRun: ai.arena.mobet.agent.AgentRunResult? = null

    /**
     * Debounce for the live summary chips.
     *
     * Highlighting stays synchronous because it is direct visual feedback on the character just
     * typed. The summary is not: rebuilding it means a full JSON parse, a PlanValidator pass and
     * re-inflating every chip, which on a large workflow overruns the frame budget and makes the
     * editor stutter exactly when the workflow is big enough to need the help.
     */
    private val summaryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val summaryTask = Runnable { refreshWorkflowSummary() }

    /** Previous service state and tint, so changes can animate instead of snapping. */
    private var lastServiceEnabled: Boolean? = null
    private var currentServiceColor: Int? = null

    /** System file picker used to import a workflow bundle. */
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::previewImport)
    }

    /** Separate picker: an attestation is evidence only and can never enter workflow import. */
    private val provenancePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::verifyProvenance)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra(MobetAccessibilityService.EXTRA_STATUS)?.let { showStatus(it) }
            intent?.getStringExtra(MobetAccessibilityService.EXTRA_TIMELINE)?.let { source ->
                runCatching { ExecutionTimelineEvent.parse(source) }.onSuccess(::renderTimeline)
            }
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
        configureMotion()
        loadWorkflowSource()
        if (savedInstanceState == null) playEntranceChoreography()

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(MobetAccessibilityService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handleServiceIntent(intent)
        verifyAndRecordBuildOnFirstLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServiceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshServiceState()
        MobetAccessibilityService.instance?.currentTimeline()?.let(::renderTimeline)
    }

    /**
     * Persists the editor draft whenever the activity stops being interactive.
     *
     * The draft used to be written only inside [runWorkflow], so a rotation, an incoming call,
     * or the system reclaiming memory mid-edit silently discarded unsaved JSON. With
     * `allowBackup=false` and no other copy of the text, that work was unrecoverable. Saving in
     * onPause covers configuration changes and process death alike, which is more reliable than
     * onSaveInstanceState alone because it also survives the app being killed in the background.
     */
    override fun onPause() {
        super.onPause()
        dotBreath?.cancel()
        persistDraft()
    }

    /**
     * Writes the current editor text to the draft slot that [loadWorkflowSource] restores.
     *
     * The caret offset is saved alongside it. Restoring the text but not the caret drops the
     * user at position zero of a long workflow after every rotation, which for an editor this
     * size is its own small data loss.
     */
    private fun persistDraft() {
        if (!::editor.isInitialized) return
        getPreferences(MODE_PRIVATE).edit()
            .putString("workflow", editor.text.toString())
            .putInt("workflow_caret", editor.selectionStart.coerceAtLeast(0))
            .apply()
    }

    override fun onDestroy() {
        summaryHandler.removeCallbacks(summaryTask)
        unregisterReceiver(receiver)
        voiceJob?.cancel()
        voiceJob = null
        super.onDestroy()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        serviceCard = findViewById(R.id.serviceCard)
        workflowCard = findViewById(R.id.workflowCard)
        serviceState = findViewById(R.id.serviceState)
        serviceDetail = findViewById(R.id.serviceDetail)
        serviceDot = findViewById(R.id.serviceDot)
        status = findViewById(R.id.status)
        editor = findViewById(R.id.editor)
        workflowSummary = findViewById(R.id.workflowSummary)
        runProgress = findViewById(R.id.runProgress)
        timelinePanel = findViewById(R.id.timelinePanel)
        timelineState = findViewById(R.id.timelineState)
        timelineGoal = findViewById(R.id.timelineGoal)
        timelineStep = findViewById(R.id.timelineStep)
        timelineDetail = findViewById(R.id.timelineDetail)

        status.movementMethod = ScrollingMovementMethod()
        status.text = getString(R.string.status_ready)

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_help -> { showHelp(); true }
                R.id.menu_export -> { exportLibrary(); true }
                R.id.menu_import -> { importLibrary(); true }
                R.id.menu_reminders -> { showReminders(); true }
                R.id.menu_build_integrity -> { showBuildIntegrity(); true }
                R.id.menu_verify_provenance -> { pickProvenance(); true }
                else -> false
            }
        }
    }

    // ── Motion ───────────────────────────────────────────────────────────────

    /**
     * True unless the user (or a test device) turned animations off globally. Every
     * animation in this file checks this gate first so the system "remove animations"
     * accessibility setting is respected instead of overridden.
     */
    private fun motionEnabled(): Boolean =
        Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f

    /**
     * Cold-start choreography: the functional cards rise and fade in one after another
     * (50ms stagger), ending on the pinned run bar, so a first launch reads as a guided
     * top-to-bottom tour of the app's structure rather than a wall of controls snapping
     * into place. Runs exactly once per process — configuration changes restore views
     * instantly — and is skipped entirely when animations are disabled.
     */
    private fun playEntranceChoreography() {
        if (!motionEnabled()) return
        val content = (findViewById<View>(R.id.contentScroll) as? androidx.core.widget.NestedScrollView)
            ?.getChildAt(0) as? android.view.ViewGroup ?: return
        val targets = buildList {
            for (i in 0 until content.childCount) add(content.getChildAt(i))
            add(findViewById(R.id.runBar))
        }
        targets.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(14).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L + index * 50L)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .withLayer()
                .start()
        }
    }

    /**
     * Wires the persistent micro-motion: summary chips fade in while the row rebuilds,
     * and the workflow card's outline warms to the primary tint for as long as the
     * editor holds focus — the two most frequent interactions get a quiet state echo.
     * Removals stay instant so rebuilding chips after each debounced keystroke never
     * ghosts stale labels over the new row.
     */
    private fun configureMotion() {
        workflowSummary.layoutTransition = android.animation.LayoutTransition().apply {
            enableTransitionType(android.animation.LayoutTransition.APPEARING)
            disableTransitionType(android.animation.LayoutTransition.DISAPPEARING)
            setDuration(android.animation.LayoutTransition.APPEARING, 180L)
            setStartDelay(android.animation.LayoutTransition.APPEARING, 0L)
        }

        editor.setOnFocusChangeListener { _, hasFocus ->
            val from = workflowCard.strokeColor
            val to = ContextCompat.getColor(
                this, if (hasFocus) R.color.mobet_primary else R.color.mobet_outline
            )
            if (from == to || !motionEnabled()) {
                workflowCard.strokeColor = to
            } else {
                android.animation.ValueAnimator.ofObject(
                    android.animation.ArgbEvaluator(), from, to
                ).apply {
                    duration = 160
                    addUpdateListener { workflowCard.strokeColor = it.animatedValue as Int }
                    start()
                }
            }
        }
    }

    /**
     * Washes the offending character in the danger tint, decaying over three beats before
     * removal. Round 11 drops the caret exactly where the parser failed; the flash is
     * what makes that landing visible on a dense line. Spans are removed by reference and
     * the text model itself is never modified, so this coexists with the highlighter and
     * with draft persistence (spans are not saved).
     */
    private fun flashErrorAt(offset: Int) {
        if (!motionEnabled()) return
        if (editor.text?.let { offset in it.indices } != true) return
        val base = ContextCompat.getColor(this, R.color.mobet_danger)
        intArrayOf(0x59, 0x38, 0x1C).forEachIndexed { step, alpha ->
            editor.postDelayed({
                val current = editor.text ?: return@postDelayed
                if (offset !in current.indices) return@postDelayed
                lastErrorFlash?.let { current.removeSpan(it) }
                val span = android.text.style.BackgroundColorSpan(
                    (base and 0x00FFFFFF) or (alpha shl 24)
                )
                current.setSpan(
                    span, offset, offset + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                lastErrorFlash = span
            }, step * 150L)
        }
        editor.postDelayed({
            lastErrorFlash?.let { editor.text?.removeSpan(it) }
            lastErrorFlash = null
        }, 520L)
    }

    /**
     * While automation is armed the service dot breathes on a slow cycle: the glance that
     * answers "is it live right now?" gets a persistent cue that does not rely on colour
     * alone. The loop is cancelled the moment the service drops, when animations are off,
     * or when the activity pauses, and the alpha reset happens before recreation so this
     * never fights the enable/disable pop.
     */
    private fun updateDotBreathing(enabled: Boolean) {
        if (enabled && motionEnabled() && dotBreath?.isRunning == true) return
        dotBreath?.cancel()
        dotBreath = null
        serviceDot.alpha = 1f
        if (!enabled || !motionEnabled()) return
        dotBreath = android.animation.ObjectAnimator.ofFloat(
            serviceDot, View.ALPHA, 1f, 0.55f
        ).apply {
            duration = 1400
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
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
        findViewById<View>(R.id.chooseTarget).setOnClickListener { chooseTargetApp() }
        findViewById<View>(R.id.editSteps).setOnClickListener { showStepBuilder() }
        findViewById<View>(R.id.generatePlan).setOnClickListener { showGoalPlanner() }
        findViewById<View>(R.id.runGoal).setOnClickListener { showAutonomousGoal() }
        findViewById<View>(R.id.dryRun).setOnClickListener { dryRunPlan() }
        findViewById<View>(R.id.inspectScreen).setOnClickListener { showInspector() }
        findViewById<View>(R.id.showDiagnostics).setOnClickListener { showDiagnostics() }
        findViewById<View>(R.id.showCaptures).setOnClickListener { showLatestCapture() }
        findViewById<View>(R.id.showAudit).setOnClickListener { showAuditLedger() }
        findViewById<View>(R.id.showMemory).setOnClickListener { showAgentMemory() }
        findViewById<View>(R.id.validatePolicy).setOnClickListener { validatePlan() }
        // The two controls with real-world consequences get tactile confirmation.
        findViewById<View>(R.id.runWorkflow).setOnClickListener {
            it.haptic(confirming = true)
            runWorkflow()
        }
        findViewById<View>(R.id.stopRun).setOnClickListener {
            it.haptic(confirming = false)
            stopAndImport()
        }

        highlighter = JsonHighlighter(
            keyColor = ContextCompat.getColor(this, R.color.mobet_json_key),
            stringColor = ContextCompat.getColor(this, R.color.mobet_json_string),
            numberColor = ContextCompat.getColor(this, R.color.mobet_json_number),
            literalColor = ContextCompat.getColor(this, R.color.mobet_json_literal),
            punctuationColor = ContextCompat.getColor(this, R.color.mobet_json_punctuation)
        )
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                // Applying spans mutates the Editable, which re-enters this callback; the flag
                // keeps that from recursing.
                if (highlighting) return
                highlighting = true
                s?.let { highlighter.apply(it) }
                highlighting = false
                summaryHandler.removeCallbacks(summaryTask)
                summaryHandler.postDelayed(summaryTask, SUMMARY_DEBOUNCE_MS)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun loadWorkflowSource() {
        val preferences = getPreferences(MODE_PRIVATE)
        // A missing or unreadable asset must not take the whole activity down on launch.
        val sample = runCatching {
            assets.open("sample_workflow.json").bufferedReader().use { it.readText() }
        }.getOrDefault(FALLBACK_WORKFLOW)
        val saved = preferences.getString("workflow", sample) ?: sample
        editor.setText(saved)
        // Clamp: the stored caret may exceed the text if the draft was replaced meanwhile.
        editor.setSelection(preferences.getInt("workflow_caret", 0).coerceIn(0, saved.length))
        refreshWorkflowSummary()
    }

    // ── Live workflow summary chips ──────────────────────────────────────────

    /**
     * Surfaces the target package, step count and policy posture as chips. Invalid JSON shows a
     * single error chip instead of failing silently at run time.
     *
     * Called directly on load and debounced while typing, since it re-parses the whole document
     * and re-inflates every chip.
     */
    private fun refreshWorkflowSummary() {
        workflowSummary.removeAllViews()
        val source = editor.text?.toString().orEmpty()
        val summary = WorkflowDocument.summarize(source) ?: return
        val parseError = summary.parseError
        if (parseError != null) {
            // Point at the breakage instead of just naming it: org.json reports a character
            // offset, the locator turns it into a line/column, and tapping the chip drops the
            // editor caret exactly there. Semantic errors carry no offset, so they fall back
            // to the message head.
            val location = JsonErrorLocator.locate(source, parseError)
            val label = "Invalid JSON" +
                (location?.let { " · line ${it.line}, col ${it.column}" }
                    ?: " · ${parseError.lineSequence().first().take(48)}")
            addChip(label, Tone.DANGER, R.drawable.ic_warning) {
                location?.let {
                    editor.requestFocus()
                    editor.setSelection(it.offset)
                    flashErrorAt(it.offset)
                }
                showStatus("Invalid JSON: $parseError", Tone.DANGER)
            }
            return
        }
        summary.packageName?.let { addChip(it.substringAfterLast('.'), Tone.NEUTRAL) }
        addChip("${summary.stepCount}/${summary.maxActions} steps", Tone.NEUTRAL)
        addChip("${summary.runtimeSeconds}s budget", Tone.NEUTRAL)
        if (summary.visualFallbacks) addChip("Visual fallback", Tone.WARNING)
        if (summary.selfHealing) addChip("Self-healing", Tone.NEUTRAL)
        val violations = summary.violations
        if (violations.isEmpty()) {
            addChip("Policy OK", Tone.SUCCESS, R.drawable.ic_check)
        } else {
            // The chip summarizes; the full violation list lives one tap away in the
            // validation report rather than hidden behind a manual menu trip.
            addChip(
                "${violations.size} policy issue${if (violations.size == 1) "" else "s"}",
                Tone.DANGER, R.drawable.ic_warning
            ) { validatePlan() }
        }
    }

    private fun addChip(label: String, tone: Tone, icon: Int? = null, onClick: (() -> Unit)? = null) {
        val chip = Chip(this).apply {
            text = label
            isClickable = onClick != null
            isCheckable = false
            if (onClick != null) setOnClickListener { onClick() }
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
            // OCR completes asynchronously; the user may have backgrounded Mobet meanwhile,
            // and showing a dialog for a destroyed activity crashes with a window-token error.
            if (isFinishing || isDestroyed) return@recognize
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

    // ── Build provenance, diagnostics, ledger, memory ───────────────────────

    /**
     * Verifies immutable build metadata and records one result per embedded manifest digest.
     * APK hashing runs off the main thread because release artifacts can be tens of megabytes.
     */
    private fun verifyAndRecordBuildOnFirstLaunch() {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { BuildIntegrity.inspect(applicationContext) }.getOrNull()
            } ?: return@launch
            val manifest = report.manifest ?: return@launch
            val marker = manifest.manifestSha256
            val preferences = getSharedPreferences("build_integrity", MODE_PRIVATE)
            if (preferences.getString("recorded_manifest", null) == marker) return@launch

            val event = if (report.verified) {
                "Build verified: v${manifest.versionName} · SHA-256 ${report.shortApkDigest}… · " +
                    "zero-network invariant"
            } else {
                "Build verification failed: v${manifest.versionName} · ${report.failures.joinToString("; ")}"
            }
            val stored = withContext(Dispatchers.IO) { AuditLedger(applicationContext).append(event) }
            if (stored) preferences.edit().putString("recorded_manifest", marker).apply()
        }
    }

    private fun pickProvenance() {
        provenancePicker.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
        )
    }

    private fun verifyProvenance(uri: android.net.Uri) {
        showBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 1_048_576) { "Attestation bundle exceeds 1 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    } ?: error("Attestation bundle could not be opened")
                    val integrity = BuildIntegrity.inspect(applicationContext)
                    val manifest = requireNotNull(integrity.manifest) { "Capability manifest unavailable" }
                    require(integrity.verified) { "Installed build integrity must pass first" }
                    SigstoreProvenance.verifyInstalledApk(applicationContext, source, manifest).also { report ->
                        if (report.verified) {
                            getSharedPreferences("build_integrity", MODE_PRIVATE).edit()
                                .putString("verified_provenance_apk", integrity.apkSha256)
                                .apply()
                            AuditLedger(applicationContext).append(
                                "SLSA provenance verified offline · APK ${integrity.shortApkDigest}… · " +
                                    "source ${report.sourceRevision?.take(12).orEmpty()}"
                            )
                        }
                    }
                }
            }
            showBusy(false)
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess(::renderProvenance).onFailure {
                showStatus("Provenance verification failed: ${it.message}", Tone.DANGER)
            }
        }
    }

    private fun renderProvenance(report: ProvenanceVerification) {
        val sheet = MobetUi.ReportSheet(this)
            .title("SLSA provenance", R.drawable.ic_check)
            .subtitle("Offline Sigstore verification")
        if (report.verified) {
            sheet.banner("✔ Signature, trust chain, transparency evidence, identity, and APK subject verified", Tone.SUCCESS)
            sheet.rows(listOf(
                Row("Signer", report.signerIdentity.orEmpty(), R.drawable.ic_check, showChevron = false),
                Row("Source", report.sourceRepository.orEmpty(), R.drawable.ic_library, showChevron = false),
                Row("Revision", report.sourceRevision.orEmpty(), R.drawable.ic_info, showChevron = false),
                Row("Builder", report.builderId.orEmpty(), R.drawable.ic_policy, showChevron = false)
            ))
        } else {
            sheet.banner("✖ Provenance is not trusted", Tone.DANGER)
                .paragraph(report.failures.joinToString("\n") { "• $it" })
        }
        sheet.paragraph(
            "Verification uses the pinned Sigstore public-good trust root bundled with this APK. " +
                "It performs no network request and requires the signed SLSA subject to match the installed APK bytes."
        ).action(getString(R.string.action_close)).show()
    }

    private fun showBuildIntegrity() {
        showBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BuildIntegrity.inspect(applicationContext) }
            }
            showBusy(false)
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess(::renderBuildIntegrity).onFailure {
                showStatus("Build verification failed: ${it.message}", Tone.DANGER)
            }
        }
    }

    private fun renderBuildIntegrity(report: BuildIntegrityReport) {
        val manifest = report.manifest
        val sheet = MobetUi.ReportSheet(this)
            .title("Build integrity", R.drawable.ic_check)
            .subtitle("Offline package and provenance verification")

        if (report.verified) {
            sheet.banner("✔ Build manifest and installed package are consistent", Tone.SUCCESS)
        } else {
            sheet.banner("✖ Integrity verification failed", Tone.DANGER)
            sheet.paragraph(report.failures.joinToString("\n") { "• $it" })
        }

        if (manifest != null) {
            val commit = manifest.commit.let { if (it.length > 12) it.take(12) else it }
            sheet.rows(
                listOf(
                    Row("App version", "${manifest.versionName} (${manifest.versionCode}) · ${manifest.buildType}", R.drawable.ic_info, showChevron = false),
                    Row("Capability identity", if (report.verified) "Package-consistent · commit $commit" else "Consistency failure · commit $commit", R.drawable.ic_check, showChevron = false),
                    Row("Signing", "${report.actualSigning} signing · manifest expects ${manifest.expectedSigning}", R.drawable.ic_policy, showChevron = false),
                    Row("Model engine", report.modelStatus, R.drawable.ic_agent, showChevron = false),
                    Row("Voice engine", report.voiceStatus, R.drawable.ic_record, showChevron = false),
                    Row("Policy version", manifest.policyVersion, R.drawable.ic_policy, showChevron = false),
                    Row("Ledger schema", manifest.ledgerSchemaVersion, R.drawable.ic_ledger, showChevron = false),
                    Row("Workflow", manifest.workflow, R.drawable.ic_diagnostics, showChevron = false),
                    Row("Attestation reference", manifest.attestation, R.drawable.ic_check, showChevron = false)
                )
            )
        }
        sheet.paragraph(
            "Installed APK SHA-256 (computed locally from the package bytes):"
        ).monospace(report.apkSha256.ifBlank { "unavailable" })
            .paragraph(
                "The embedded manifest authenticates its canonical payload and is checked against " +
                    "the installed app ID, version, signing certificate, declared permissions, and " +
                    "required safety invariants. The APK computes its own final digest because a " +
                    "file cannot embed its final whole-file hash without changing that hash."
            )
            .action(getString(R.string.action_close))
            .show()
    }

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
        // Ledger inspection and export do not require Accessibility to be enabled. Using the same
        // encrypted store directly also exposes the first-launch build-verification event before
        // the automation service has ever started.
        val ledger = MobetAccessibilityService.instance?.auditLedger()
            ?: AuditLedger(applicationContext)
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
            sheet.action("Export evidence", primary = true) {
                exportAuditLedger(ledger)
            }
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

    /** Creates and shares a redacted evidence bundle without exposing ledger storage directly. */
    private fun exportAuditLedger(ledger: AuditLedger) {
        showBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Keep verification and snapshot under one monitor so a runner append cannot
                    // land between them and make the exported source verdict stale.
                    val (entries, sourceVerified) = synchronized(ledger) {
                        ledger.entries() to (ledger.verify() == null)
                    }
                    val integrity = BuildIntegrity.inspect(applicationContext)
                    val manifest = requireNotNull(integrity.manifest) {
                        "The embedded build manifest is unavailable"
                    }
                    require(integrity.apkSha256.isNotBlank()) { "The installed APK digest is unavailable" }
                    val identity = LedgerBuildIdentity(
                        version = manifest.versionName,
                        versionCode = manifest.versionCode,
                        apkSha256 = integrity.apkSha256,
                        manifestSha256 = manifest.manifestSha256,
                        commit = manifest.commit,
                        signing = integrity.actualSigning,
                        capabilityVerified = integrity.verified,
                        provenanceVerifiedOnDevice = getSharedPreferences("build_integrity", MODE_PRIVATE)
                            .getString("verified_provenance_apk", null) == integrity.apkSha256,
                        policyVersion = manifest.policyVersion,
                        ledgerSchemaVersion = manifest.ledgerSchemaVersion
                    )
                    val bundle = LedgerExport.json(
                        entries = entries,
                        deviceRun = ledger.deviceRunId(),
                        build = identity,
                        sourceVerified = sourceVerified
                    )
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    WorkflowTransfer.writeShareable(
                        applicationContext,
                        bundle,
                        "mobet-ledger-$stamp.json"
                    )
                }
            }
            showBusy(false)
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { uri ->
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            WorkflowTransfer.shareIntent(uri, "Mobet ledger evidence"),
                            "Export audit evidence"
                        )
                    )
                }.onFailure { showStatus("Could not share ledger: ${it.message}", Tone.DANGER) }
            }.onFailure { showStatus("Could not export ledger: ${it.message}", Tone.DANGER) }
        }
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
        handlePresenceRun(value)
        handleViewImport(value)
        openWorkflowFromReminder(value)
        handleConfirmation(value)
    }

    /**
     * QS tile / launcher shortcut (docs/FRONTIER.md pillar 4): the click is the user's
     * explicit gesture, so the pinned workflow is loaded and run immediately — the ordinary
     * pipeline still applies, including every confirmation gate. Without a pin the gesture
     * opens the library so the user can pin what the tile should fire.
     */
    private fun handlePresenceRun(value: Intent?) {
        if (value?.action != PresenceLauncher.ACTION_RUN_PINNED) return
        intent.action = null
        val pinned = PresenceLauncher.resolve(this)
        if (pinned == null) {
            showStatus(
                "Nothing is pinned to the shade yet — run a workflow, or pin one from its library sheet",
                Tone.WARNING
            )
            loadFromLibrary()
            return
        }
        editor.setText(pinned.source)
        persistDraft()
        showStatus("Running pinned workflow “${pinned.name}”", Tone.SUCCESS)
        runWorkflow()
    }

    /**
     * Share-target intake: a `.mobet.json` bundle (or any JSON document) opened into Mobet
     * from a file manager or another app. The review sheet is the same one the in-app picker
     * uses — stream-capped read, per-entry validation, explicit Import — so this surface adds
     * no new trust decisions, only a new route to the existing ones (docs/THREAT_MODEL.md).
     */
    private fun handleViewImport(value: Intent?) {
        if (value?.action != Intent.ACTION_VIEW) return
        val uri = value.data ?: return
        intent.action = null
        previewImport(uri)
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
        // Flag secrets whose Keystore key no longer decrypts them. Listing an unreadable secret
        // as if it were fine sends the user hunting through their workflow when the real fix is
        // to re-enter the value.
        val unreadable = names.filterNot(store::isReadable).toSet()
        val rows = buildList {
            add(Row("Add or replace a secret", "Encrypted with an Android Keystore key",
                R.drawable.ic_secret))
            names.forEach { name ->
                val broken = name in unreadable
                add(
                    Row(
                        title = name,
                        subtitle = if (broken)
                            "Unreadable \u2014 the encryption key changed. Re-add it to fix, or tap to delete"
                        else "Tap to delete",
                        icon = if (broken) R.drawable.ic_warning else R.drawable.ic_delete,
                        badgeColor = if (broken)
                            ContextCompat.getColor(this@MainActivity, R.color.mobet_danger) else null
                    )
                )
            }
        }
        MobetUi.picker(
            activity = this,
            title = "Encrypted secrets",
            subtitle = when {
                names.isEmpty() -> "No secrets stored yet"
                unreadable.isEmpty() -> "${names.size} stored"
                else -> "${names.size} stored \u00B7 ${unreadable.size} unreadable"
            },
            icon = R.drawable.ic_secret,
            rows = rows
        ) { index ->
            if (index == 0) showSecretEditor(store)
            else {
                val name = names[index - 1]
                confirmDestructive(
                    "Delete secret?",
                    "“$name” will be permanently removed from encrypted storage. This cannot be " +
                        "undone — Mobet cannot read a stored secret back to restore it.",
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
        var voiceTranscriptReviewed = false

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
            .setNeutralButton("🎙 Dictate") { _, _ -> }
            .setPositiveButton("Preview plan") { _, _ ->
                IntentToPlanPipeline.prepare(
                    goal.value,
                    evidence.value,
                    snapshot.packageName,
                    if (voiceTranscriptReviewed) IntentSource.VOICE_TRANSCRIPT else IntentSource.TYPED
                ).onSuccess { preview ->
                    showAutonomousPlanPreview(preview, ocr.isChecked, model.isChecked, service)
                }.onFailure {
                    showStatus("Goal rejected: ${it.message}", Tone.DANGER)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .apply {
                // Keep the review dialog open for dictation: a neutral button would normally
                // dismiss it. The transcript lands IN the field — dictation is an input
                // method, never execution authority (docs/THREAT_MODEL.md).
                getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dictateGoal(goal) { voiceTranscriptReviewed = true }
                }
            }
    }

    private fun showAutonomousPlanPreview(
        preview: ai.arena.mobet.planner.AgentPlanPreview,
        allowOcr: Boolean,
        allowModel: Boolean,
        service: MobetAccessibilityService
    ) {
        MobetUi.ReportSheet(this)
            .title("Execution plan", R.drawable.ic_plan)
            .subtitle("Explain mode · no device actions have run")
            .banner("Review required before execution", Tone.WARNING)
            .monospace(preview.explanation(RunMode.EXPLAIN))
            .paragraph(
                "On-device OCR: ${if (allowOcr) "consented" else "off"}\n" +
                    "Model assistance: ${if (allowModel) "enabled as an untrusted proposer" else "off"}\n\n" +
                    "The candidate route is selected from fresh accessibility observations at " +
                    "runtime. Every action is revalidated before execution."
            )
            .action("Dry run") {
                MobetUi.ReportSheet(this)
                    .title("Autonomous dry run", R.drawable.ic_dryrun)
                    .subtitle("Simulation only · the device was not touched")
                    .monospace(preview.explanation(RunMode.DRY_RUN))
                    .action(getString(R.string.action_close))
                    .show()
            }
            .action("Execute", primary = true) {
                showBusy(true)
                service.startAutonomous(preview.asAgentGoal(allowOcr, allowModel))
            }
            .action(getString(R.string.action_cancel))
            .show()
    }

    // ── Voice goals (1.0; RECORD_AUDIO, on-device only) ─────────────────────

    private var voiceJob: Job? = null
    private var dictationDialog: androidx.appcompat.app.AlertDialog? = null

    /** Voice is an input method only: explicit permission, offline engine, editable transcript. */
    private fun dictateGoal(target: MobetUi.Field, onTranscriptReady: () -> Unit = {}) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            showStatus(
                "Microphone needed once for dictation — audio is recognized on this device only",
                Tone.WARNING
            )
            return
        }
        val engine = VoiceEngines.select(applicationContext)
        if (!engine.isAvailable) {
            showStatus("No offline voice engine is available — type the goal instead", Tone.WARNING)
            return
        }
        voiceJob?.cancel()
        val dialog = MobetUi.dialog(this)
            .setTitle("Listening")
            .setIcon(R.drawable.ic_record)
            .setMessage("Speak the goal.\n\nRecognized entirely on this device; audio is not retained.")
            .setNegativeButton(R.string.action_cancel, null)
            .show()
        dictationDialog = dialog
        dialog.setOnDismissListener {
            if (dictationDialog === dialog) {
                voiceJob?.cancel()
                voiceJob = null
                dictationDialog = null
            }
        }
        voiceJob = lifecycleScope.launch {
            runCatching {
                engine.transcribe(AudioInput.Microphone { partial ->
                    if (!isFinishing && !isDestroyed) {
                        dialog.setMessage("Speak the goal.\n\n${partial.take(500)}")
                    }
                })
            }.onSuccess { transcript ->
                // Dismiss first so its cancellation hook cannot affect a later session.
                dictationDialog = null
                dialog.dismiss()
                target.input.setText(transcript.text)
                target.input.setSelection(transcript.text.length)
                onTranscriptReady()
                // Never echo transcript text into diagnostics or the persistent ledger.
                showStatus(
                    "Offline transcript ready — review and edit it before starting the run",
                    Tone.SUCCESS
                )
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) return@onFailure
                dictationDialog = null
                dialog.dismiss()
                showStatus("${error.message ?: "Recognition unavailable"} — type the goal instead", Tone.WARNING)
            }
            voiceJob = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.firstOrNull() ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            showStatus(
                if (granted) "Microphone granted — tap 🎙 Dictate again"
                else "No microphone; voice goals stay off — typing works as always",
                if (granted) Tone.SUCCESS else Tone.WARNING
            )
        }
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
            "open “Network & internet” then tap “Wi-Fi”; verify “On” appears",
            lines = 3
        )
        MobetUi.dialog(this)
            .setTitle("Generate grounded plan")
            .setIcon(R.drawable.ic_plan)
            .setMessage(
                "Target: ${snapshot.packageName}\n" +
                    "Only elements verified on the captured screen can be planned.\n\n" +
                    "Clauses: tap/open/fill/scroll/wait/back/home · verify “X” appears · " +
                    "if “X” appears then … · repeat … until “X” appears max N. " +
                    "Separate with “then”, “;” or new lines."
            )
            .setView(MobetUi.formContainer(this, input.layout))
            .setPositiveButton("Generate") { _, _ ->
                WorkflowSynthesizer.synthesize(input.value, snapshot)
                    .onSuccess(::presentSynthesis)
                    .onFailure { showStatus("Generator rejected goal: ${it.message}", Tone.DANGER) }
            }
            .setNeutralButton("Recipes") { _, _ -> showRecipePicker(snapshot) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Parameterised authoring patterns. A recipe only expands into the same goal clauses the
     * grammar already accepts, so it is grounded, risk-gated and validated exactly like typed
     * text — convenience, never extra authority.
     */
    private fun showRecipePicker(snapshot: ai.arena.mobet.automation.ScreenSnapshot) {
        val recipes = WorkflowRecipes.catalogue
        MobetUi.picker(
            activity = this,
            title = "Workflow recipes",
            subtitle = "Grounded in ${snapshot.packageName} — nothing runs until you press Run",
            icon = R.drawable.ic_plan,
            rows = recipes.map { Row(title = it.title, subtitle = it.summary, icon = R.drawable.ic_plan) }
        ) { index ->
            val recipe = recipes[index]
            val fields = recipe.parameters.map { parameter ->
                parameter to MobetUi.Field(this, parameter.label, parameter.hint)
            }
            MobetUi.dialog(this)
                .setTitle(recipe.title)
                .setIcon(R.drawable.ic_plan)
                .setMessage(recipe.summary)
                .setView(MobetUi.formContainer(this, *fields.map { it.second.layout }.toTypedArray()))
                .setPositiveButton("Generate") { _, _ ->
                    val values = fields.associate { (parameter, field) -> parameter.key to field.value }
                    recipe.synthesize(values, snapshot)
                        .onSuccess(::presentSynthesis)
                        .onFailure { showStatus("Recipe rejected: ${it.message}", Tone.DANGER) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    /** Shows the generated plan's rationale; the document only changes if the user inserts it. */
    private fun presentSynthesis(result: SynthesizedWorkflow) {
        MobetUi.ReportSheet(this)
            .title("Generated plan", R.drawable.ic_plan)
            .subtitle("${result.stepCount} steps · policy-validated · nothing has run")
            .monospace(result.report())
            .action(getString(R.string.action_close))
            .action("Insert", primary = true) {
                editor.setText(result.json)
                showStatus("Plan inserted and policy-validated — review before running", Tone.SUCCESS)
            }
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
            val source = editor.text.toString()
            val workflow = Workflow.parse(source)
            val violations = PlanValidator.validate(workflow)
            val sheet = MobetUi.ReportSheet(this).title("Policy validation", R.drawable.ic_policy)
            if (violations.isEmpty()) {
                sheet.banner("✔ Approved by policy", Tone.SUCCESS)
                    .monospace(
                        "Target      ${workflow.packageName}\n" +
                            "Actions     ${workflow.steps.size} / ${workflow.policy.maxActions}\n" +
                            "Runtime     ${workflow.policy.maxRuntimeMs} ms\n" +
                            "Visual      ${if (workflow.policy.allowVisualFallbacks) "allowed" else "blocked"}\n" +
                            "Self-heal   ${if (workflow.policy.allowSelfHealing) "allowed" else "blocked"}\n\n" +
                            // Advisory robustness read-out; policy already approved the plan.
                            PlanQuality.analyze(workflow).summary()
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
            // Authoring-time repair: re-ground drifted selectors against the screen the user is
            // on right now. No device effects, no run-time self-healing — the repaired plan is
            // shown as a report and only replaces the document if the user inserts it.
            val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
            if (snapshot != null && snapshot.packageName == workflow.packageName) {
                sheet.action("Re-ground to screen") {
                    WorkflowRepair.repair(source, snapshot)
                        .onSuccess(::presentSynthesis)
                        .onFailure { showStatus("Cannot re-ground: ${it.message}", Tone.WARNING) }
                }
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

    /** Opens the visual step editor over the same JSON the text editor holds. */
    private fun showStepBuilder() {
        ai.arena.mobet.ui.StepBuilder(
            activity = this,
            readSource = { editor.text.toString() },
            writeSource = { editor.setText(it) },
            notify = { message, tone -> showStatus(message, tone) },
            undo = { message, restore -> reportUndoable(message, restore) },
            appPicker = { onChosen ->
                val apps = launchableApps()
                if (apps.isEmpty()) showStatus("No launchable apps found", Tone.WARNING)
                else MobetUi.picker(
                    activity = this,
                    title = "Choose app to launch",
                    subtitle = "Remember to add it to policy.allowedPackages",
                    icon = R.drawable.ic_apps,
                    rows = apps.map { appRow(it) }
                ) { index -> onChosen(apps[index].packageName) }
            }
        ).show()
    }

    // ── Target app picker ────────────────────────────────────────────────────

    /** An installed, launchable app with its real launcher icon. */
    data class InstalledApp(
        val label: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable?
    )

    /** Installed apps that expose a launcher activity, excluding Mobet itself. */
    private fun launchableApps(): List<InstalledApp> =
        packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != packageName }
            .map { info ->
                InstalledApp(
                    label = packageManager.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    // Loading icons is cheap enough for a one-shot picker and makes the list
                    // scannable at a glance instead of a wall of identical glyphs.
                    icon = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
                )
            }
            .sortedBy { it.label.lowercase() }

    private fun appRow(app: InstalledApp, badge: String? = null) = Row(
        title = app.label,
        subtitle = app.packageName,
        icon = R.drawable.ic_apps,
        iconDrawable = app.icon,
        badge = badge,
        searchKey = "${app.label} ${app.packageName}".lowercase()
    )

    /**
     * Rewrites the workflow's target package from a list of installed apps, so the user never
     * has to know that Settings is `com.android.settings`.
     *
     * The old target is removed from `allowedPackages` and the new one added, keeping any extra
     * packages the user authored for cross-app `launch` steps.
     */
    private fun chooseTargetApp() {
        val apps = launchableApps()
        if (apps.isEmpty()) {
            showStatus("No launchable apps found", Tone.WARNING)
            return
        }
        val current = runCatching { Workflow.parse(editor.text.toString()).packageName }.getOrNull()
        MobetUi.picker(
            activity = this,
            title = "Choose target app",
            subtitle = current?.let { "Currently: $it" } ?: "Sets \"package\" and the policy allowlist",
            icon = R.drawable.ic_apps,
            rows = apps.map { appRow(it, badge = if (it.packageName == current) "current" else null) }
        ) { index -> applyTargetPackage(apps[index].packageName, apps[index].label) }
    }

    private fun applyTargetPackage(target: String, label: String) {
        try {
            editor.setText(WorkflowDocument.retarget(editor.text.toString(), target))
            showStatus("Target set to $label ($target)", Tone.SUCCESS)
        } catch (error: Exception) {
            showStatus("Could not set target: ${error.message}", Tone.DANGER)
        }
    }

    // ── Backup: export / import ──────────────────────────────────────────────

    /**
     * Offers the library as a shareable `.json` bundle.
     *
     * Because `allowBackup` is false, this is the only way a library survives an uninstall —
     * which is exactly what a signing-key change forces. Secret values are never included.
     */
    private fun exportLibrary() {
        val library = getSharedPreferences("library", MODE_PRIVATE)
        val names = library.all.keys.sorted()
        if (names.isEmpty()) {
            showStatus("The workflow library is empty — nothing to export", Tone.WARNING)
            return
        }
        val bundle = WorkflowTransfer.exportBundle(this)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        MobetUi.ReportSheet(this)
            .title("Export workflows", R.drawable.ic_save)
            .subtitle("${names.size} workflow${if (names.size == 1) "" else "s"} · secrets are not included")
            .paragraph(
                "Android backup is disabled for Mobet, so uninstalling erases the library. Save " +
                    "this bundle somewhere safe before reinstalling.\n\nSecret values stay in " +
                    "encrypted storage and are never written to the file — a workflow that uses " +
                    "{{secret:name}} exports only the reference."
            )
            .rows(names.map { Row(it, null, R.drawable.ic_library, showChevron = false) })
            .action("Share bundle", primary = true) {
                runCatching {
                    val uri = WorkflowTransfer.writeShareable(this, bundle, "mobet-workflows-$stamp.json")
                    startActivity(Intent.createChooser(
                        WorkflowTransfer.shareIntent(uri, "Mobet workflow bundle"),
                        "Export ${names.size} workflows"
                    ))
                }.onFailure { showStatus("Could not export: ${it.message}", Tone.DANGER) }
            }
            .action("Copy JSON") {
                copyToClipboard("Mobet workflow bundle", bundle)
                showStatus("Bundle copied to clipboard", Tone.SUCCESS)
            }
            .action(getString(R.string.action_close))
            .show()
    }

    /** Launches the system file picker; the result is handled by [importPicker]. */
    private fun importLibrary() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
        runCatching { importPicker.launch(intent) }
            .onFailure { showStatus("No file picker available on this device", Tone.DANGER) }
    }

    /**
     * Previews a chosen bundle before writing anything.
     *
     * Every entry is validated by [Workflow.parse] first, and the user sees exactly what will
     * be imported — including entries that failed — so a malformed or hostile file cannot
     * quietly populate the library.
     */
    private fun previewImport(uri: android.net.Uri) {
        val source = WorkflowTransfer.readUri(this, uri).getOrElse {
            showStatus("Could not read file: ${it.message}", Tone.DANGER)
            return
        }
        val result = WorkflowTransfer.parseBundle(source).getOrElse {
            showStatus("Not a valid Mobet bundle: ${it.message}", Tone.DANGER)
            return
        }
        val sheet = MobetUi.ReportSheet(this)
            .title("Workflow quarantine report", R.drawable.ic_library)
            .subtitle("${result.validCount} of ${result.workflows.size} eligible for import")
            .monospace(
                "Targets: ${result.packageCount} package declarations\n" +
                    "Confirmations: ${result.confirmationCount}\n" +
                    "Unsigned workflows: ${result.unsignedCount}\n" +
                    "Content hashes: ${if (result.allHashesVerified) "verified" else "legacy or failed"}\n" +
                    "Network-dependent steps: none supported"
            )
        if (result.validCount < result.workflows.size) {
            sheet.banner("⚠ ${result.workflows.size - result.validCount} entr" +
                "${if (result.workflows.size - result.validCount == 1) "y" else "ies"} " +
                "failed validation and will be skipped", Tone.WARNING)
        }
        sheet.rows(result.workflows.map { entry ->
            Row(
                title = entry.name,
                subtitle = entry.detail,
                icon = if (entry.valid) R.drawable.ic_check else R.drawable.ic_warning,
                badgeColor = ContextCompat.getColor(
                    this, if (entry.valid) R.color.mobet_success else R.color.mobet_danger
                ),
                showChevron = false
            )
        })
        sheet.paragraph(
            "Imported workflows are added to the library; existing names are kept and the new " +
                "copy is numbered. Review any workflow before running it."
        )
        if (result.validCount > 0) {
            sheet.action("Import ${result.validCount}", primary = true) {
                val written = WorkflowTransfer.commit(this, result.workflows)
                showStatus("Imported $written workflow${if (written == 1) "" else "s"}", Tone.SUCCESS)
            }
        }
        sheet.action(getString(R.string.action_cancel)).show()
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
            val name = names[index]
            MobetUi.ReportSheet(this)
                .title(name, R.drawable.ic_library)
                .subtitle(rows[index].subtitle)
                .action("Load", primary = true) {
                    library.getString(name, null)?.let(editor::setText)
                    showStatus("Loaded “$name”", Tone.SUCCESS)
                }
                .action("Remind me") { scheduleReminder(name) }
                .action("Pin to shade") {
                    val source = library.getString(name, null) ?: return@action
                    PresenceLauncher.pin(this@MainActivity, name, source)
                    showStatus("Pinned “$name” — the QS tile and launcher shortcut now run it", Tone.SUCCESS)
                }
                .action(getString(R.string.action_delete), destructive = true) {
                    confirmDestructive(
                        "Delete “$name”?",
                        "The saved workflow is removed from the library. Export first if you " +
                            "want to keep a copy.",
                        getString(R.string.action_delete)
                    ) {
                        val backup = library.getString(name, null)
                        library.edit().remove(name).apply()
                        RunReminder.cancel(this, name)
                        if (backup == null) showStatus("Deleted “$name”", Tone.SUCCESS)
                        else reportUndoable("Deleted “$name”") {
                            library.edit().putString(name, backup).apply()
                        }
                    }
                }
                .show()
        }
    }

    // ── Run reminders ────────────────────────────────────────────────────────

    /**
     * Schedules a reminder for a saved workflow.
     *
     * This intentionally does not auto-run anything. Mobet's safety model depends on a human
     * being present to answer confirmations and hit Stop, so the alarm posts a notification
     * that opens the app with the workflow loaded — the user still presses Run.
     */
    private fun scheduleReminder(name: String) {
        val now = java.util.Calendar.getInstance()
        android.app.TimePickerDialog(
            this,
            { _, hour, minute ->
                val target = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    // A time already past today means the user meant tomorrow.
                    if (timeInMillis <= System.currentTimeMillis()) {
                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }
                }
                if (RunReminder.schedule(this, name, target.timeInMillis)) {
                    showStatus(
                        "Reminder set for ${RunReminder.format(target.timeInMillis)} — " +
                            "Mobet will prompt you, not run it",
                        Tone.SUCCESS
                    )
                } else {
                    showStatus("Could not set the reminder", Tone.DANGER)
                }
            },
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            true
        ).show()
    }

    private fun showReminders() {
        val pending = RunReminder.pending(this)
        val sheet = MobetUi.ReportSheet(this)
            .title("Run reminders", R.drawable.ic_diagnostics)
            .subtitle("Mobet reminds you — it never runs a workflow on its own")
        if (pending.isEmpty()) {
            sheet.empty(
                "No reminders scheduled",
                "Open the workflow library and choose Remind me to schedule one.",
                R.drawable.ic_diagnostics
            )
        } else {
            sheet.rows(pending.map { (name, at) ->
                Row(name, RunReminder.format(at), R.drawable.ic_run, showChevron = false)
            })
            sheet.action("Cancel all", destructive = true) {
                pending.forEach { RunReminder.cancel(this, it.first) }
                showStatus("All reminders cancelled", Tone.SUCCESS)
            }
        }
        sheet.paragraph(
            "Unattended execution is intentionally unsupported: a workflow running with nobody " +
                "present could not be confirmed, supervised, or stopped. Reminders keep you in " +
                "the loop while still nudging you at the right time."
        )
        sheet.action(getString(R.string.action_close)).show()
    }

    /** Opens a workflow from the library when launched via a reminder notification. */
    private fun openWorkflowFromReminder(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_WORKFLOW) return
        this.intent.action = null
        val name = intent.getStringExtra(RunReminder.EXTRA_WORKFLOW_NAME) ?: return
        val source = getSharedPreferences("library", MODE_PRIVATE).getString(name, null)
        if (source == null) {
            showStatus("Workflow “$name” is no longer in the library", Tone.WARNING)
            return
        }
        editor.setText(source)
        showStatus("Loaded “$name” from a reminder — review it, then press Run", Tone.SUCCESS)
    }

    // ── Recording ────────────────────────────────────────────────────────────

    private fun startRecorder() {
        val service = MobetAccessibilityService.instance ?: run { requireService(); return }
        val apps = launchableApps()
        if (apps.isEmpty()) {
            showStatus("No launchable apps found", Tone.WARNING)
            return
        }
        MobetUi.picker(
            activity = this,
            title = "Record taps in app",
            subtitle = "Mobet captures the selectors you touch, then you import them as steps",
            icon = R.drawable.ic_record,
            rows = apps.map { appRow(it) }
        ) { index ->
            val app = apps[index]
            recordingPackage = if (service.launchTarget(app.packageName)) {
                service.startRecording()
                showStatus("Recording in ${app.label} — return and tap Stop to import", Tone.SUCCESS)
                app.packageName
            } else {
                showStatus("Could not launch ${app.label}", Tone.DANGER)
                null
            }
        }
    }

    private fun importRecordedSteps(source: String) {
        try {
            val recorded = JSONArray(source)
            val target = recordingPackage
            recordingPackage = null
            if (recorded.length() == 0) {
                showStatus("Stopped. No recorded taps to import")
                return
            }
            // Preferred path: synthesize a *workflow* from the trace (duplicate events removed,
            // scroll runs coalesced, waits inserted, risk confirmations added, policy validated)
            // instead of pasting raw events that would replay against a half-loaded screen.
            if (target != null) {
                val synthesized = TraceSynthesizer.synthesize(
                    recorded = source,
                    packageName = target,
                    options = ai.arena.mobet.synthesis.TraceOptions(
                        name = "Recorded in $target"
                    )
                )
                if (synthesized.isSuccess) {
                    presentSynthesis(synthesized.getOrThrow())
                    return
                }
            }
            editor.setText(WorkflowDocument.appendSteps(editor.text.toString(), recorded, target))
            showStatus(
                "Imported ${recorded.length()} recorded steps" +
                    if (target != null) " — target allowlist includes $target" else "",
                Tone.SUCCESS
            )
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
            persistDraft()
            // The run is the strongest signal of what the QS tile/shortcut should fire:
            // every successful start re-pins it (docs/FRONTIER.md pillar 4).
            PresenceLauncher.pin(this, workflow.name, source)
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
        // Only import when a capture was actually running; otherwise every plain Stop
        // surfaced a confusing "No recorded taps to import" message.
        if (service.isRecording()) importRecordedSteps(service.stopRecording())
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
        // Motion here is informational, not decorative: enabling the service is the one state
        // change that unblocks the whole app, so it animates rather than snapping.
        animateServiceColor(color)
        serviceCard.strokeColor = (color and 0x00FFFFFF) or 0x55000000
        if (enabled != lastServiceEnabled && lastServiceEnabled != null) {
            serviceDot.animate().scaleX(1.6f).scaleY(1.6f).setDuration(160)
                .withEndAction {
                    serviceDot.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
                }.start()
            // The animation tells sighted users the gate opened/closed; a screen reader needs
            // the same transition announced or it simply never happened for them.
            serviceCard.announceForAccessibility(
                getString(if (enabled) R.string.service_enabled else R.string.service_disabled)
            )
        }
        lastServiceEnabled = enabled
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

    /** Cross-fades the service indicator between its previous and new tint. */
    private fun animateServiceColor(target: Int) {
        val from = currentServiceColor
        currentServiceColor = target
        if (from == null) {
            applyServiceColor(target)
            return
        }
        if (from == target) return
        android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, target)
            .apply {
                duration = 320
                addUpdateListener { applyServiceColor(it.animatedValue as Int) }
                start()
            }
    }

    private fun applyServiceColor(color: Int) {
        serviceDot.background?.mutate()?.let { DrawableCompat.setTint(it, color) }
        serviceState.setTextColor(color)
    }

    private fun renderTimeline(event: ExecutionTimelineEvent) {
        timelinePanel.visibility = View.VISIBLE
        timelineState.text = "${event.state.name} · ${event.mode.uppercase(Locale.US)}"
        timelineState.setTextColor(
            ContextCompat.getColor(
                this,
                when (event.state) {
                    ExecutionTimelineEvent.State.SUCCEEDED -> R.color.mobet_success
                    ExecutionTimelineEvent.State.HALTED -> R.color.mobet_danger
                    ExecutionTimelineEvent.State.WAITING,
                    ExecutionTimelineEvent.State.RECOVERING -> R.color.mobet_warning
                    else -> R.color.mobet_primary
                }
            )
        )
        timelineGoal.text = "Goal: ${event.goal}"
        timelineStep.text = buildString {
            if (event.step != null) {
                append("Step ").append(event.step)
                event.totalSteps?.let { append(" of ").append(it) }
            } else append("Preparing run")
            event.subgoal?.let { append("\nSubgoal: ").append(it) }
            event.action?.let { append("\nAction: ").append(it) }
        }
        timelineDetail.text = buildList {
            event.screenFingerprint?.let { add("Screen      $it") }
            event.confidence?.let { add("Confidence  $it%") }
            event.risk?.let { add("Risk        $it") }
            event.evidence?.let { add("Evidence    $it") }
            event.policy?.let { add("Policy      $it") }
            event.recovery?.let { add("Recovery    $it") }
            event.stopReason?.let { add("Stopped     $it") }
        }.joinToString("\n")
        showBusy(
            event.state in setOf(
                ExecutionTimelineEvent.State.PLANNING,
                ExecutionTimelineEvent.State.RUNNING,
                ExecutionTimelineEvent.State.WAITING,
                ExecutionTimelineEvent.State.RECOVERING
            )
        )
        if (event.state == ExecutionTimelineEvent.State.SUCCEEDED) offerCrystallization()
    }

    /**
     * A verified autonomous run knows a route that worked. Offer to turn it into a deterministic
     * workflow so the next run is a replay instead of another exploration — reviewed, editable and
     * subject to the same policy gate as anything else.
     */
    private fun offerCrystallization() {
        val (run, goal) = MobetAccessibilityService.instance?.lastCrystallizableRun() ?: return
        if (run === crystallizedRun) return
        MobetUi.snack(this, "Autonomous goal verified — save the route as a workflow?", Tone.SUCCESS, "Save") {
            crystallizedRun = run
            AgentCrystallizer.crystallize(run, goal)
                .onSuccess(::presentSynthesis)
                .onFailure { showStatus("Cannot crystallize this run: ${it.message}", Tone.WARNING) }
        }
    }

    private fun showBusy(busy: Boolean) {
        if (busy == (runProgress.visibility == View.VISIBLE)) return
        if (busy) {
            runProgress.alpha = 0f
            runProgress.visibility = View.VISIBLE
            runProgress.animate().alpha(1f).setDuration(180).start()
        } else {
            runProgress.animate().alpha(0f).setDuration(180)
                .withEndAction { runProgress.visibility = View.GONE }.start()
        }
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

    /**
     * Reports a reversible destructive action and offers a single-tap Undo.
     *
     * Deleting a saved workflow or a step used to be unrecoverable — with `allowBackup="false"`
     * and no version history, a mis-tap meant retyping it. The caller supplies a restore
     * closure; the snackbar keeps it alive for the duration of the bar.
     */
    private fun reportUndoable(message: String, undo: () -> Unit) {
        appendLog(message)
        MobetUi.snack(this, message, Tone.SUCCESS, getString(R.string.action_undo)) {
            undo()
            showStatus("Restored", Tone.SUCCESS)
        }
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

    /**
     * Tactile feedback for consequential controls.
     *
     * CONFIRM/REJECT only exist from API 30; below that we fall back to the long-standing
     * KEYBOARD_TAP so older devices still get a cue rather than silence.
     */
    private fun View.haptic(confirming: Boolean) {
        val effect = when {
            Build.VERSION.SDK_INT >= 30 && confirming ->
                android.view.HapticFeedbackConstants.CONFIRM
            Build.VERSION.SDK_INT >= 30 ->
                android.view.HapticFeedbackConstants.REJECT
            else -> android.view.HapticFeedbackConstants.KEYBOARD_TAP
        }
        runCatching { performHapticFeedback(effect) }
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
        const val ACTION_OPEN_WORKFLOW = "ai.arena.mobet.OPEN_WORKFLOW"

        /** Runtime-permission request code for voice-goal dictation (RECORD_AUDIO). */
        const val REQUEST_RECORD_AUDIO = 42

        /**
         * Minimal valid workflow, used only if the bundled sample asset cannot be read.
         *
         * Returning a parseable document rather than an empty string keeps the summary chips
         * and the visual builder in a sane state instead of showing a parse error on first run.
         */
        private const val FALLBACK_WORKFLOW = """{
  "name": "New workflow",
  "steps": [
    { "action": "wait", "text": "", "timeoutMs": 5000 }
  ]
}"""

        /** Quiet period before the editor's summary chips are recomputed. */
        private const val SUMMARY_DEBOUNCE_MS = 250L

        /** Runner phrases that mean no operation is in flight any more. */
        private val TERMINAL_MARKERS = listOf(
            "completed", "failed", "stopped", "aborted", "denied", "rejected", "cancelled"
        )
    }
}
