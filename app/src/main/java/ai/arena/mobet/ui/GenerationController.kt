package ai.arena.mobet.ui

import ai.arena.mobet.R
import ai.arena.mobet.agent.AgentRunResult
import ai.arena.mobet.automation.MobetAccessibilityService
import ai.arena.mobet.automation.ScreenSnapshot
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.planner.PlanSimulator
import ai.arena.mobet.policy.PlanValidator
import ai.arena.mobet.synthesis.AgentCrystallizer
import ai.arena.mobet.synthesis.PlanDiff
import ai.arena.mobet.synthesis.PlanQuality
import ai.arena.mobet.synthesis.QualitySeverity
import ai.arena.mobet.synthesis.SynthesizedWorkflow
import ai.arena.mobet.synthesis.WorkflowRecipes
import ai.arena.mobet.synthesis.WorkflowRepair
import ai.arena.mobet.synthesis.WorkflowSynthesizer
import ai.arena.mobet.ui.MobetUi.Row
import ai.arena.mobet.ui.MobetUi.Tone
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/**
 * What the generation flows need from the screen that hosts them.
 *
 * Keeping this narrow is the point: the controller can drive dialogs and sheets, read the current
 * document and replace it, report status, and hand a saved plan to the reminder scheduler — and
 * nothing else. The activity keeps ownership of its views.
 */
interface GenerationHost {
    val activity: AppCompatActivity

    /** The workflow JSON currently in the editor. */
    fun currentSource(): String

    /** Replaces the editor contents; only ever called from an explicit user action. */
    fun replaceSource(json: String)

    /** Reports a completed change with an Undo affordance, restoring on request. */
    fun reportUndoable(message: String, undo: () -> Unit)

    fun status(message: String, tone: Tone = Tone.NEUTRAL)

    /** Opens the existing reminder scheduler for a saved library entry. */
    fun scheduleReminder(name: String)
}

/**
 * All workflow *generation* user flows in one place: goal planning, recipes, the synthesis report,
 * insert/save/schedule, dry run, policy validation with re-grounding, the robustness sheet, and
 * crystallizing a verified autonomous run.
 *
 * These lived in `MainActivity`, which had grown past 2,400 lines and mixed them with window
 * insets, motion, provenance, ledger export and run control. Nothing here is new behaviour; the
 * value is that the generation surface is now one readable unit with one explicit dependency.
 */
class GenerationController(private val host: GenerationHost) {

    private val activity: AppCompatActivity get() = host.activity

    /** Last autonomous run already offered for crystallization; prevents repeat prompts. */
    private var crystallizedRun: AgentRunResult? = null

    // ── Goal planning ────────────────────────────────────────────────────────

    fun showGoalPlanner() {
        val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
        if (snapshot == null || snapshot.elements.isEmpty()) {
            host.status("Visit the target app first so the goal can be grounded in its screen", Tone.WARNING)
            return
        }
        val input = MobetUi.Field(
            activity,
            "Goal",
            "open “Network & internet” then tap “Wi-Fi”; verify “On” appears",
            lines = 3
        )
        MobetUi.dialog(activity)
            .setTitle("Generate grounded plan")
            .setIcon(R.drawable.ic_plan)
            .setMessage(
                "Target: ${snapshot.packageName}\n" +
                    "Targets are grounded in this screen, and in screens seen earlier this session.\n\n" +
                    "Clauses: tap/open/fill/scroll/wait/back/home · verify “X” appears · " +
                    "if “X” appears then … · repeat … until “X” appears max N. " +
                    "Separate with “then”, “;” or new lines."
            )
            .setView(MobetUi.formContainer(activity, input.layout))
            .setPositiveButton("Generate") { _, _ ->
                WorkflowSynthesizer.synthesize(input.value, snapshot)
                    .onSuccess(::presentSynthesis)
                    .onFailure { host.status("Generator rejected goal: ${it.message}", Tone.DANGER) }
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
    fun showRecipePicker(snapshot: ScreenSnapshot) {
        val recipes = WorkflowRecipes.catalogue
        MobetUi.picker(
            activity = activity,
            title = "Workflow recipes",
            subtitle = "Grounded in ${snapshot.packageName} — nothing runs until you press Run",
            icon = R.drawable.ic_plan,
            rows = recipes.map { Row(title = it.title, subtitle = it.summary, icon = R.drawable.ic_plan) }
        ) { index ->
            val recipe = recipes[index]
            val fields = recipe.parameters.map { parameter ->
                parameter to MobetUi.Field(activity, parameter.label, parameter.hint)
            }
            MobetUi.dialog(activity)
                .setTitle(recipe.title)
                .setIcon(R.drawable.ic_plan)
                .setMessage(recipe.summary)
                .setView(MobetUi.formContainer(activity, *fields.map { it.second.layout }.toTypedArray()))
                .setPositiveButton("Generate") { _, _ ->
                    val values = fields.associate { (parameter, field) -> parameter.key to field.value }
                    recipe.synthesize(values, snapshot)
                        .onSuccess(::presentSynthesis)
                        .onFailure { host.status("Recipe rejected: ${it.message}", Tone.DANGER) }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ── Report, insert, save ─────────────────────────────────────────────────

    /** Shows the generated plan's rationale; the document only changes if the user inserts it. */
    fun presentSynthesis(result: SynthesizedWorkflow) {
        // Insert overwrites the editor, so show exactly what would change first.
        val diff = PlanDiff.between(host.currentSource(), result.workflow)
        MobetUi.ReportSheet(activity)
            .title("Generated plan", R.drawable.ic_plan)
            .subtitle("${result.stepCount} steps · policy-validated · nothing has run")
            .monospace(result.report() + (diff?.let { "\n" + it.render() } ?: ""))
            .action(activity.getString(R.string.action_close))
            // A generated plan used to dead-end in the editor. Saving it names it, puts it in the
            // library (the unit every export bundle and reminder is addressed by), and makes the
            // usual export/schedule paths available without retyping anything.
            .action("Save & schedule") { insertPlan(result); saveGeneratedPlan(result) }
            .action("Insert", primary = true) { insertPlan(result) }
            .show()
    }

    /**
     * Inserting replaces whatever the user had written, so the replaced document is captured and
     * offered back. A destructive action with no way back is the one thing an authoring tool
     * cannot ask a person to accept on faith — the report's diff explains the change, and Undo
     * makes accepting it reversible.
     */
    private fun insertPlan(result: SynthesizedWorkflow) {
        val replaced = host.currentSource()
        host.replaceSource(result.json)
        if (replaced.isBlank()) {
            host.status("Plan inserted and policy-validated — review before running", Tone.SUCCESS)
        } else {
            host.reportUndoable("Plan inserted — the previous document was replaced") {
                host.replaceSource(replaced)
            }
        }
    }

    /**
     * Names the generated plan, stores it in the library, and offers to schedule a reminder.
     *
     * Scheduling stays a *reminder*: Mobet prompts at the chosen time, it never starts a run on
     * its own. Library entries are what `WorkflowTransfer` exports as signed v2 bundles, so this
     * is also the on-ramp to sharing a generated plan.
     */
    private fun saveGeneratedPlan(result: SynthesizedWorkflow) {
        val suggested = result.workflow.name.take(60)
        val input = MobetUi.Field(activity, "Workflow name", "Saved to the library and exportable as a bundle")
        input.input.setText(suggested)
        MobetUi.dialog(activity)
            .setTitle("Save generated plan")
            .setIcon(R.drawable.ic_save)
            .setView(MobetUi.formContainer(activity, input.layout))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.value.ifBlank { suggested }
                if (name.isBlank()) {
                    host.status("A workflow name is required", Tone.WARNING)
                    return@setPositiveButton
                }
                activity.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                    .putString(name, result.json).apply()
                MobetUi.snack(
                    activity,
                    "Saved “$name” — exportable as a bundle",
                    Tone.SUCCESS,
                    "Schedule"
                ) { host.scheduleReminder(name) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── Preflight ────────────────────────────────────────────────────────────

    fun dryRunPlan() {
        try {
            val workflow = Workflow.parse(host.currentSource())
            val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
            val report = PlanSimulator.simulate(workflow, snapshot)
            MobetUi.ReportSheet(activity)
                .title("Dry run", R.drawable.ic_dryrun)
                .subtitle("Simulated against the last snapshot — the device is not touched")
                .monospace(report)
                .action(activity.getString(R.string.action_close))
                .show()
        } catch (error: Exception) {
            host.status("Invalid workflow: ${error.message}", Tone.DANGER)
        }
    }

    fun validatePlan() {
        try {
            val source = host.currentSource()
            val workflow = Workflow.parse(source)
            val violations = PlanValidator.validate(workflow)
            val sheet = MobetUi.ReportSheet(activity).title("Policy validation", R.drawable.ic_policy)
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
                sheet.banner(
                    "✖ Rejected — ${violations.size} violation${if (violations.size == 1) "" else "s"}",
                    Tone.DANGER
                ).rows(
                    violations.map { violation ->
                        Row(
                            title = violation.message,
                            subtitle = violation.step?.let { "Step $it" } ?: "Plan level",
                            icon = R.drawable.ic_warning,
                            showChevron = false
                        )
                    }
                )
            }
            // Authoring-time repair: re-ground drifted selectors against the screen the user is
            // on right now. No device effects, no run-time self-healing — the repaired plan is
            // shown as a report and only replaces the document if the user inserts it.
            val snapshot = MobetAccessibilityService.instance?.latestSnapshot()
            if (snapshot != null && snapshot.packageName == workflow.packageName) {
                sheet.action("Re-ground to screen") {
                    WorkflowRepair.repair(source, snapshot)
                        .onSuccess(::presentSynthesis)
                        .onFailure { host.status("Cannot re-ground: ${it.message}", Tone.WARNING) }
                }
            }
            sheet.action(activity.getString(R.string.action_close)).show()
        } catch (error: Exception) {
            host.status("Invalid workflow: ${error.message}", Tone.DANGER)
        }
    }

    /** Advisory robustness findings; never a gate, so the sheet offers no "fix" action. */
    fun showQualityReport(quality: PlanQuality) {
        val sheet = MobetUi.ReportSheet(activity)
            .title("Plan robustness", R.drawable.ic_policy)
            .subtitle("Advisory only — policy validation is separate and authoritative")
            .monospace("Grade       ${quality.grade} (${quality.score}/100)")
        if (quality.findings.isEmpty()) {
            sheet.paragraph("No robustness concerns found in this plan.")
        } else {
            sheet.rows(
                quality.findings.map { finding ->
                    Row(
                        title = finding.message,
                        subtitle = finding.step?.let { "Step $it" } ?: "Plan level",
                        icon = when (finding.severity) {
                            QualitySeverity.WARNING -> R.drawable.ic_warning
                            QualitySeverity.ADVICE -> R.drawable.ic_policy
                            QualitySeverity.INFO -> R.drawable.ic_check
                        },
                        showChevron = false
                    )
                }
            )
        }
        sheet.action(activity.getString(R.string.action_close)).show()
    }

    // ── Crystallization ──────────────────────────────────────────────────────

    /**
     * A verified autonomous run knows a route that worked. Offer to turn it into a deterministic
     * workflow so the next run is a replay instead of another exploration — reviewed, editable and
     * subject to the same policy gate as anything else.
     */
    fun offerCrystallization() {
        val (run, goal) = MobetAccessibilityService.instance?.lastCrystallizableRun() ?: return
        if (run === crystallizedRun) return
        MobetUi.snack(
            activity,
            "Autonomous goal verified — save the route as a workflow?",
            Tone.SUCCESS,
            "Save"
        ) {
            crystallizedRun = run
            AgentCrystallizer.crystallize(run, goal)
                .onSuccess(::presentSynthesis)
                .onFailure { host.status("Cannot crystallize this run: ${it.message}", Tone.WARNING) }
        }
    }
}
