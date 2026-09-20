package ai.arena.mobet.ui

import ai.arena.mobet.R
import ai.arena.mobet.automation.Workflow
import ai.arena.mobet.policy.RiskEngine
import ai.arena.mobet.policy.RiskTier
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Visual editor for a workflow's step list.
 *
 * The builder is a *view* over the same JSON the text editor holds — it parses on open and
 * writes back on every mutation — rather than a parallel model. That keeps the two editors
 * from diverging and means the builder inherits validation, risk scoring and policy checks for
 * free instead of reimplementing them.
 *
 * It deliberately cannot author raw gesture actions (`tappoint`, `swipe`, `visualtap`): those
 * are coordinate-based, policy-gated behind `allowVisualFallbacks`, and belong in the text
 * editor where their parameters are explicit.
 */
class StepBuilder(
    private val activity: Activity,
    private val readSource: () -> String,
    private val writeSource: (String) -> Unit,
    private val notify: (String, MobetUi.Tone) -> Unit,
    /** Reports a reversible change and surfaces an Undo affordance. */
    private val undo: (String, () -> Unit) -> Unit,
    /** Supplies installed apps so a `launch` step can be picked rather than typed. */
    private val appPicker: ((String) -> Unit) -> Unit
) {

    /** Actions the visual builder can create, with the fields each one needs. */
    private enum class Kind(
        val action: String,
        val label: String,
        val description: String,
        val icon: Int,
        val needsSelector: Boolean = false,
        val needsValue: Boolean = false,
        val needsMessage: Boolean = false,
        val needsPackage: Boolean = false
    ) {
        WAIT("wait", "Wait for text", "Pause until an element appears", R.drawable.ic_dryrun, needsSelector = true),
        TAP("tap", "Tap", "Tap a control by its visible text", R.drawable.ic_run, needsSelector = true),
        FILL("fill", "Fill field", "Focus a field and type a value", R.drawable.ic_format, needsSelector = true, needsValue = true),
        SCROLL("scroll", "Scroll", "Scroll a scrollable container forward", R.drawable.ic_arrow_down, needsSelector = true),
        CONFIRM("confirm", "Confirm", "Pause and ask the user to approve", R.drawable.ic_check, needsMessage = true),
        LAUNCH("launch", "Launch app", "Switch to another allow-listed app", R.drawable.ic_apps, needsPackage = true),
        DELAY("delay", "Delay", "Wait a fixed number of milliseconds", R.drawable.ic_diagnostics),
        BACK("back", "Back", "Press the system Back button", R.drawable.ic_chevron_right),
        HOME("home", "Home", "Press the system Home button", R.drawable.ic_accessibility)
    }

    fun show() {
        val root = runCatching { JSONObject(readSource()) }.getOrElse {
            notify("Fix the workflow JSON before using the builder: ${it.message}", MobetUi.Tone.DANGER)
            return
        }
        val steps = root.optJSONArray("steps") ?: JSONArray().also { root.put("steps", it) }
        val sheet = MobetUi.ReportSheet(activity)
            .title("Edit steps", R.drawable.ic_steps)
            .subtitle(stepsSummary(root))

        if (steps.length() == 0) {
            sheet.empty(
                "No steps yet",
                "Add the first action to build this workflow.",
                R.drawable.ic_steps
            )
        } else {
            val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for (i in 0 until steps.length()) {
                container.addView(stepCard(steps, i, root, sheet))
            }
            sheet.custom(container)
        }

        sheet.action("Add step", primary = true) { chooseKind(root, steps) }
        sheet.action(activity.getString(R.string.action_close))
        sheet.show()
    }

    private fun stepsSummary(root: JSONObject): String {
        val flow = runCatching { Workflow.parse(root.toString()) }.getOrNull()
            ?: return "Unvalidated draft"
        val elevated = flow.steps.count { RiskEngine.assess(it).tier >= RiskTier.ELEVATED }
        return buildString {
            append("${flow.steps.size} step${if (flow.steps.size == 1) "" else "s"}")
            if (elevated > 0) append(" · $elevated need confirmation")
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun stepCard(
        steps: JSONArray,
        index: Int,
        root: JSONObject,
        sheet: MobetUi.ReportSheet
    ): View {
        val item = steps.getJSONObject(index)
        val view = LayoutInflater.from(activity).inflate(R.layout.item_step_card, null, false)
        view.findViewById<TextView>(R.id.stepIndex).text = (index + 1).toString()
        view.findViewById<TextView>(R.id.stepAction).text = item.optString("action")
        view.findViewById<TextView>(R.id.stepDetail).text = describe(item)

        // Surface the same risk tier the validator will enforce, so a step that will be
        // rejected for missing a confirm is visible while editing rather than at run time.
        val risk = runCatching {
            RiskEngine.assess(
                ai.arena.mobet.automation.Step(
                    action = item.optString("action").lowercase(),
                    selector = ai.arena.mobet.automation.Selector(
                        text = item.optString("text").takeIf(String::isNotBlank),
                        viewId = item.optString("viewId").takeIf(String::isNotBlank),
                        description = item.optString("description").takeIf(String::isNotBlank)
                    ),
                    value = item.optString("value").takeIf(String::isNotBlank),
                    message = item.optString("message").takeIf(String::isNotBlank)
                )
            )
        }.getOrNull()
        view.findViewById<TextView>(R.id.stepRisk).apply {
            if (risk != null && risk.tier >= RiskTier.ELEVATED) {
                visibility = View.VISIBLE
                text = "${risk.tier.name} · needs a confirm before it"
                setTextColor(ContextCompat.getColor(
                    activity,
                    if (risk.tier == RiskTier.CRITICAL) R.color.mobet_danger else R.color.mobet_warning
                ))
            } else visibility = View.GONE
        }

        view.findViewById<MaterialButton>(R.id.stepUp).apply {
            isEnabled = index > 0
            setOnClickListener { swap(steps, index, index - 1, root, sheet) }
        }
        view.findViewById<MaterialButton>(R.id.stepDown).apply {
            isEnabled = index < steps.length() - 1
            setOnClickListener { swap(steps, index, index + 1, root, sheet) }
        }
        view.findViewById<MaterialButton>(R.id.stepDelete).setOnClickListener {
            // Keep a copy so the removal can be reversed from the snackbar.
            val removed = steps.getJSONObject(index)
            steps.remove(index)
            writeSource(root.toString(2))
            sheet.dismiss()
            undo("Step ${index + 1} removed") {
                reinsert(root, index, removed)
            }
            show()
        }
        view.setOnClickListener { editStep(root, steps, index, sheet) }
        return view
    }

    private fun describe(item: JSONObject): String {
        val parts = buildList {
            item.optString("text").takeIf(String::isNotBlank)?.let { add("text: ${preview(it)}") }
            item.optString("viewId").takeIf(String::isNotBlank)?.let { add("id: ${preview(it)}") }
            item.optString("description").takeIf(String::isNotBlank)?.let { add("desc: ${preview(it)}") }
            item.optString("package").takeIf(String::isNotBlank)?.let { add("package: ${preview(it)}") }
            item.optString("value").takeIf(String::isNotBlank)?.let { add("value: ${preview(it)}") }
            item.optString("message").takeIf(String::isNotBlank)?.let { add("“${preview(it)}”") }
            if (item.optString("action") == "delay") add("${item.optLong("delayMs", 300)} ms")
        }
        return parts.joinToString(" · ").ifBlank { "no parameters" }
    }

    /**
     * Renders one field of a step card safely.
     *
     * A workflow can be imported from a shared file, so these strings are untrusted. Newlines are
     * flattened -- the card allots two lines and a multi-line value would otherwise push the
     * controls around or hide the rest of the summary -- and long values are clipped so one
     * pathological field cannot crowd out the others.
     *
     * A `{{secret:name}}` reference is shown as-is on purpose. It is a *reference*, not a value;
     * the stored secret is never read here, and hiding the name would leave the user unable to
     * tell which credential a step uses.
     */
    private fun preview(value: String): String {
        val flattened = value.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= MAX_FIELD_PREVIEW) flattened
        else flattened.take(MAX_FIELD_PREVIEW).trimEnd() + "…"
    }

    // ── Mutation ─────────────────────────────────────────────────────────────

    private fun swap(steps: JSONArray, from: Int, to: Int, root: JSONObject, sheet: MobetUi.ReportSheet) {
        if (to < 0 || to >= steps.length()) return
        val a = steps.getJSONObject(from)
        val b = steps.getJSONObject(to)
        steps.put(from, b)
        steps.put(to, a)
        commit(root, sheet, "Step order updated")
    }

    /** Re-inserts a removed step at its original position and reopens the builder. */
    private fun reinsert(root: JSONObject, index: Int, item: JSONObject) {
        val steps = root.optJSONArray("steps") ?: JSONArray().also { root.put("steps", it) }
        val rebuilt = JSONArray()
        var inserted = false
        for (i in 0 until steps.length()) {
            if (i == index) {
                rebuilt.put(item)
                inserted = true
            }
            rebuilt.put(steps.get(i))
        }
        if (!inserted) rebuilt.put(item)
        root.put("steps", rebuilt)
        writeSource(root.toString(2))
        show()
    }

    /** Writes the mutated JSON back to the editor and reopens the builder in its new state. */
    private fun commit(root: JSONObject, sheet: MobetUi.ReportSheet, message: String) {
        writeSource(root.toString(2))
        notify(message, MobetUi.Tone.SUCCESS)
        sheet.dismiss()
        show()
    }

    private fun chooseKind(root: JSONObject, steps: JSONArray) {
        val kinds = Kind.values().toList()
        MobetUi.picker(
            activity = activity,
            title = "Add a step",
            subtitle = "Gesture and OCR actions stay in the JSON editor",
            icon = R.drawable.ic_add,
            rows = kinds.map { MobetUi.Row(it.label, it.description, it.icon) }
        ) { index -> stepForm(root, steps, kinds[index], null) }
    }

    private fun editStep(root: JSONObject, steps: JSONArray, index: Int, sheet: MobetUi.ReportSheet) {
        val item = steps.getJSONObject(index)
        val kind = Kind.values().firstOrNull { it.action == item.optString("action").lowercase() }
        if (kind == null) {
            notify("“${item.optString("action")}” can only be edited as JSON", MobetUi.Tone.WARNING)
            return
        }
        sheet.dismiss()
        stepForm(root, steps, kind, index)
    }

    /** Builds a form containing only the fields the chosen action actually uses. */
    private fun stepForm(root: JSONObject, steps: JSONArray, kind: Kind, editIndex: Int?) {
        val existing = editIndex?.let(steps::getJSONObject)
        val fields = mutableListOf<View>()

        val selector = if (kind.needsSelector) MobetUi.Field(
            activity, "Visible text", "Exactly as it appears on screen"
        ).also { field ->
            existing?.optString("text")?.takeIf(String::isNotBlank)?.let(field.input::setText)
            fields.add(field.layout)
        } else null

        val value = if (kind.needsValue) MobetUi.Field(
            activity, "Value to type", "Use {{secret:name}} to insert a stored secret"
        ).also { field ->
            existing?.optString("value")?.takeIf(String::isNotBlank)?.let(field.input::setText)
            fields.add(field.layout)
        } else null

        val message = if (kind.needsMessage) MobetUi.Field(
            activity, "Prompt shown to you", "e.g. Allow Mobet to submit this form?"
        ).also { field ->
            existing?.optString("message")?.takeIf(String::isNotBlank)?.let(field.input::setText)
            fields.add(field.layout)
        } else null

        val packageField = if (kind.needsPackage) MobetUi.Field(
            activity, "Package name", "Must also be in policy.allowedPackages"
        ).also { field ->
            existing?.optString("package")?.takeIf(String::isNotBlank)?.let(field.input::setText)
            // Typing a package name from memory is error-prone; offer the installed-app list.
            field.layout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CUSTOM
            field.layout.setEndIconDrawable(R.drawable.ic_apps)
            field.layout.setEndIconContentDescription(R.string.action_choose_target)
            field.layout.setEndIconOnClickListener {
                appPicker { chosen -> field.input.setText(chosen) }
            }
            fields.add(field.layout)
        } else null

        val delay = if (kind == Kind.DELAY) MobetUi.Field(
            activity, "Milliseconds", "How long to pause"
        ).also { field ->
            field.input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            field.input.setText((existing?.optLong("delayMs", 1_000) ?: 1_000L).toString())
            fields.add(field.layout)
        } else null

        MobetUi.dialog(activity)
            .setTitle(if (editIndex == null) "Add ${kind.label.lowercase()}" else "Edit ${kind.label.lowercase()}")
            .setIcon(kind.icon)
            .setView(MobetUi.formContainer(activity, *fields.toTypedArray()))
            .setPositiveButton(if (editIndex == null) "Add" else "Save") { _, _ ->
                val item = JSONObject().put("action", kind.action)
                selector?.value?.takeIf(String::isNotEmpty)?.let { item.put("text", it) }
                value?.value?.takeIf(String::isNotEmpty)?.let { item.put("value", it) }
                message?.value?.takeIf(String::isNotEmpty)?.let { item.put("message", it) }
                packageField?.value?.takeIf(String::isNotEmpty)?.let { item.put("package", it) }
                delay?.value?.toLongOrNull()?.let { item.put("delayMs", it.coerceIn(0, 10_000)) }

                // Preserve fields the visual form does not expose so editing a step never
                // silently drops timeouts, retries or conditionals authored in JSON.
                existing?.keys()?.forEach { key ->
                    if (!item.has(key) && key !in FORM_KEYS) item.put(key, existing.get(key))
                }

                if (editIndex == null) steps.put(item) else steps.put(editIndex, item)
                writeSource(root.toString(2))
                notify(
                    if (editIndex == null) "Added ${kind.label.lowercase()}" else "Step ${editIndex + 1} updated",
                    MobetUi.Tone.SUCCESS
                )
                show()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> show() }
            .show()
    }

    private companion object {
        /** Keys the visual form owns; everything else is carried through untouched. */
        val FORM_KEYS = setOf("action", "text", "value", "message", "package", "delayMs")

        /** Per-field clip length on a step card, which allots two lines to the whole summary. */
        const val MAX_FIELD_PREVIEW = 60
    }
}
