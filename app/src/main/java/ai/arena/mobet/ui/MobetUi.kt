package ai.arena.mobet.ui

import ai.arena.mobet.R
import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Presentation helpers shared by every Mobet surface.
 *
 * This layer holds no automation logic — it only knows how to render reports, pickers, forms
 * and confirmations. Each surface is inflated from a themed layout so light/dark mode, font
 * scaling and 48dp touch targets stay consistent without call sites hand-rolling colours.
 */
object MobetUi {

    enum class Tone { NEUTRAL, SUCCESS, WARNING, DANGER }

    /** Lists at or above this length get a search field. */
    private const val SEARCH_THRESHOLD = 12

    // ── Units ────────────────────────────────────────────────────────────────

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Snackbars ────────────────────────────────────────────────────────────

    /** Transient feedback anchored above the run bar so it never covers the primary action. */
    fun snack(
        activity: Activity,
        message: CharSequence,
        tone: Tone = Tone.NEUTRAL,
        actionLabel: String? = null,
        action: (() -> Unit)? = null
    ) {
        val root = activity.findViewById<View>(R.id.rootCoordinator) ?: return
        val bar = Snackbar.make(root, message, if (actionLabel != null) 7_000 else Snackbar.LENGTH_LONG)
        activity.findViewById<View>(R.id.runBar)?.let(bar::setAnchorView)
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 5
        toneColor(activity, tone)?.let(bar::setTextColor)
        if (actionLabel != null && action != null) bar.setAction(actionLabel) { action() }
        bar.show()
    }

    @ColorInt
    private fun toneColor(context: Context, tone: Tone): Int? = when (tone) {
        Tone.SUCCESS -> ContextCompat.getColor(context, R.color.mobet_success)
        Tone.WARNING -> ContextCompat.getColor(context, R.color.mobet_warning)
        Tone.DANGER -> ContextCompat.getColor(context, R.color.mobet_danger)
        Tone.NEUTRAL -> null
    }

    // ── Report bottom sheet ──────────────────────────────────────────────────

    /**
     * The scrollable sheet Mobet uses for every long-form report: diagnostics, audit ledger,
     * dry run, agent memory, OCR output and validation results.
     */
    class ReportSheet(private val activity: Activity) {
        private val dialog = BottomSheetDialog(activity)
        private val view: View =
            LayoutInflater.from(activity).inflate(R.layout.sheet_report, null, false)
        private val body: LinearLayout = view.findViewById(R.id.sheetBody)
        private val actions: LinearLayout = view.findViewById(R.id.sheetActions)
        private val inflater = LayoutInflater.from(activity)

        init {
            dialog.setContentView(view)
            // Reports should open at a readable height rather than a thin peek.
            dialog.behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

        fun title(text: CharSequence, @DrawableRes icon: Int? = null) = apply {
            view.findViewById<TextView>(R.id.sheetTitle).text = text
            val iconView = view.findViewById<ImageView>(R.id.sheetIcon)
            if (icon == null) iconView.visibility = View.GONE else iconView.setImageResource(icon)
        }

        fun subtitle(text: CharSequence?) = apply {
            val label = view.findViewById<TextView>(R.id.sheetSubtitle)
            label.text = text
            label.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        /** Coloured status strip used for pass/fail verdicts such as ledger integrity. */
        fun banner(text: CharSequence, tone: Tone) = apply {
            val banner = view.findViewById<TextView>(R.id.sheetBanner)
            banner.text = text
            banner.visibility = View.VISIBLE
            banner.setTextColor(toneColor(activity, tone)
                ?: ContextCompat.getColor(activity, R.color.mobet_on_surface_variant))
            val container = when (tone) {
                Tone.DANGER -> R.color.mobet_danger_container
                Tone.WARNING -> R.color.mobet_warning_container
                else -> R.color.mobet_code_background
            }
            banner.background = ContextCompat.getDrawable(activity, R.drawable.bg_status_surface)
                ?.mutate()?.apply { setTint(ContextCompat.getColor(activity, container)) }
        }

        /** Text added to the sheet, retained so [exportable] can copy or share exactly that. */
        private val textBlocks = mutableListOf<CharSequence>()

        fun paragraph(text: CharSequence) = apply {
            textBlocks += text
            body.addView(TextView(activity).apply {
                this.text = text
                setTextAppearance(R.style.TextAppearance_Mobet_Body)
                setTextIsSelectable(true)
            }, spacing())
        }

        /** Monospaced block for logs, hashes and generated reports. */
        fun monospace(text: CharSequence) = apply {
            textBlocks += text
            body.addView(TextView(activity).apply {
                this.text = text
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(activity, R.color.mobet_code_text))
                setLineSpacing(0f, 1.25f)
                setTextIsSelectable(true)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_status_surface)
                val pad = activity.dp(12)
                setPadding(pad, pad, pad, pad)
            }, spacing())
        }

        fun rows(items: List<Row>, onClick: ((Int) -> Unit)? = null) = apply {
            items.forEachIndexed { index, row ->
                body.addView(buildRow(activity, row) {
                    if (onClick != null) {
                        dismiss()
                        onClick(index)
                    }
                })
            }
        }

        fun empty(
            title: CharSequence,
            message: CharSequence,
            @DrawableRes icon: Int = R.drawable.ic_info
        ) = apply {
            val empty = inflater.inflate(R.layout.view_empty_state, body, false)
            empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(icon)
            empty.findViewById<TextView>(R.id.emptyTitle).text = title
            empty.findViewById<TextView>(R.id.emptyBody).apply {
                text = message
                visibility = if (message.isBlank()) View.GONE else View.VISIBLE
            }
            body.addView(empty)
        }

        fun custom(child: View) = apply { body.addView(child, spacing()) }

        /** Trailing action button. Exactly one action should normally be `primary`. */
        fun action(
            label: CharSequence,
            primary: Boolean = false,
            destructive: Boolean = false,
            dismissAfter: Boolean = true,
            onClick: (() -> Unit)? = null
        ) = apply {
            val layout = when {
                primary -> R.layout.widget_sheet_action_primary
                destructive -> R.layout.widget_sheet_action_danger
                else -> R.layout.widget_sheet_action_text
            }
            val button = inflater.inflate(layout, actions, false) as MaterialButton
            button.text = label
            button.setOnClickListener {
                if (dismissAfter) dismiss()
                onClick?.invoke()
            }
            actions.addView(button)
            (button.layoutParams as? LinearLayout.LayoutParams)?.marginStart = activity.dp(8)
        }

        /**
         * Adds Copy and Share for the sheet's text content.
         *
         * Reports were previously read-only and transient: a diagnostics dump or an audit extract
         * could be read on the phone and nowhere else, which is useless precisely when someone is
         * trying to get help with a failure. Only text the sheet already displays is exported, and
         * sharing goes through the system chooser, so the user picks the destination.
         */
        fun exportable(subject: CharSequence) = apply {
            exportSubject = subject
        }

        private var exportSubject: CharSequence? = null

        private fun exportText(): String = textBlocks.joinToString("\n\n").trim()

        fun show() {
            val subject = exportSubject
            if (subject != null && exportText().isNotBlank()) {
                action(activity.getString(R.string.action_copy_report), dismissAfter = false) {
                    val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(subject, exportText())
                    )
                    // Android 13+ shows its own copy confirmation; a second one would be noise.
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        snack(activity, activity.getString(R.string.report_copied), Tone.SUCCESS)
                    }
                }
                action(activity.getString(R.string.action_share_report), dismissAfter = false) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                        putExtra(android.content.Intent.EXTRA_TEXT, exportText())
                    }
                    runCatching { activity.startActivity(android.content.Intent.createChooser(intent, subject)) }
                }
            }
            if (actions.childCount == 0) action(activity.getString(R.string.action_close))
            dialog.show()
        }

        fun dismiss() = dialog.dismiss()

        private fun spacing() = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = activity.dp(10)
        }
    }

    /** A single list entry used by pickers and report sheets. */
    data class Row(
        val title: CharSequence,
        val subtitle: CharSequence? = null,
        @DrawableRes val icon: Int? = null,
        val badge: CharSequence? = null,
        @ColorInt val badgeColor: Int? = null,
        val showChevron: Boolean = true,
        /** Real drawable (e.g. an app's launcher icon), preferred over [icon] when present. */
        val iconDrawable: android.graphics.drawable.Drawable? = null,
        /** Lowercased haystack used by the picker's search box; defaults to title + subtitle. */
        val searchKey: String = "",
        /** Set when the row should not tint its drawable with the primary colour. */
        val preserveIconColor: Boolean = false
    ) {
        fun matches(query: String): Boolean {
            if (query.isBlank()) return true
            val haystack = searchKey.ifBlank {
                (title.toString() + " " + (subtitle ?: "")).lowercase()
            }
            return haystack.contains(query.lowercase())
        }
    }

    private fun buildRow(context: Context, row: Row, onClick: () -> Unit): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_picker_row, null, false)
        view.findViewById<TextView>(R.id.rowTitle).text = row.title
        view.findViewById<TextView>(R.id.rowSubtitle).apply {
            text = row.subtitle
            visibility = if (row.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.rowIcon).apply {
            when {
                row.iconDrawable != null -> {
                    setImageDrawable(row.iconDrawable)
                    // App launcher icons carry their own brand colour; tinting would destroy it.
                    imageTintList = null
                    layoutParams = layoutParams.apply {
                        width = context.dp(28)
                        height = context.dp(28)
                    }
                }
                row.icon != null -> {
                    setImageResource(row.icon)
                    if (row.preserveIconColor) imageTintList = null
                }
                else -> visibility = View.GONE
            }
        }
        view.findViewById<TextView>(R.id.rowBadge).apply {
            if (row.badge.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                text = row.badge
                visibility = View.VISIBLE
                val tint = row.badgeColor
                    ?: ContextCompat.getColor(context, R.color.mobet_on_surface_variant)
                setTextColor(tint)
                background = ContextCompat.getDrawable(context, R.drawable.bg_badge)?.mutate()
                    ?.apply { setTint((tint and 0x00FFFFFF) or 0x24000000) }
            }
        }
        view.findViewById<ImageView>(R.id.rowChevron).visibility =
            if (row.showChevron) View.VISIBLE else View.GONE
        view.setOnClickListener { onClick() }
        view.contentDescription = listOfNotNull(row.title, row.subtitle, row.badge)
            .joinToString(", ")
        return view
    }

    // ── Pickers ──────────────────────────────────────────────────────────────

    /**
     * Icon-and-subtitle list that replaces `AlertDialog.setItems`.
     *
     * Lists longer than [SEARCH_THRESHOLD] gain a filter box — the installed-app picker can run
     * to several hundred entries, where scrolling alone is unusable. The selection callback
     * always receives the index into the *original* list, so filtering cannot cause the caller
     * to act on the wrong item.
     */
    fun picker(
        activity: Activity,
        title: CharSequence,
        subtitle: CharSequence? = null,
        @DrawableRes icon: Int? = null,
        rows: List<Row>,
        emptyTitle: CharSequence = "Nothing here yet",
        emptyBody: CharSequence = "",
        onSelect: (Int) -> Unit
    ) {
        if (rows.isEmpty()) {
            ReportSheet(activity).title(title, icon).subtitle(subtitle)
                .empty(emptyTitle, emptyBody).show()
            return
        }

        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_picker, null, false)
        dialog.setContentView(view)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        view.findViewById<TextView>(R.id.pickerTitle).text = title
        view.findViewById<TextView>(R.id.pickerSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<ImageView>(R.id.pickerIcon).apply {
            if (icon == null) visibility = View.GONE else setImageResource(icon)
        }

        val list: LinearLayout = view.findViewById(R.id.pickerList)
        val noMatches: TextView = view.findViewById(R.id.pickerNoMatches)

        fun render(query: String) {
            list.removeAllViews()
            var shown = 0
            rows.forEachIndexed { index, row ->
                if (!row.matches(query)) return@forEachIndexed
                shown++
                list.addView(buildRow(activity, row) {
                    dialog.dismiss()
                    onSelect(index)
                })
            }
            noMatches.visibility = if (shown == 0) View.VISIBLE else View.GONE
        }
        render("")

        if (rows.size >= SEARCH_THRESHOLD) {
            view.findViewById<View>(R.id.pickerSearchLayout).visibility = View.VISIBLE
            view.findViewById<TextInputEditText>(R.id.pickerSearch)
                .addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) =
                        render(s?.toString().orEmpty())
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                })
        }

        val actions: LinearLayout = view.findViewById(R.id.pickerActions)
        val close = LayoutInflater.from(activity)
            .inflate(R.layout.widget_sheet_action_text, actions, false) as MaterialButton
        close.text = activity.getString(R.string.action_close)
        close.setOnClickListener { dialog.dismiss() }
        actions.addView(close)

        dialog.show()
    }


    // ── Forms ────────────────────────────────────────────────────────────────

    /** Outlined text field with a floating label, helper text and autofill disabled. */
    class Field(
        context: Context,
        label: CharSequence,
        helper: CharSequence? = null,
        lines: Int = 1,
        password: Boolean = false
    ) {
        val layout: TextInputLayout = LayoutInflater.from(context)
            .inflate(R.layout.widget_field, null, false) as TextInputLayout
        val input: TextInputEditText = layout.findViewById(R.id.fieldInput)

        init {
            layout.hint = label
            layout.helperText = helper
            layout.isHelperTextEnabled = helper != null
            if (password) {
                layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else if (lines > 1) {
                input.minLines = lines
                input.isSingleLine = false
                input.gravity = Gravity.TOP or Gravity.START
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        }

        val value: String get() = input.text?.toString()?.trim().orEmpty()
    }

    /** Vertical container with dialog-appropriate horizontal insets. */
    fun formContainer(context: Context, vararg children: View): ViewGroup =
        (LayoutInflater.from(context).inflate(R.layout.dialog_form, null) as LinearLayout).apply {
            children.forEach { child ->
                addView(child, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = context.dp(6)
                })
            }
        }

    fun checkBox(context: Context, label: CharSequence): MaterialCheckBox =
        (LayoutInflater.from(context).inflate(R.layout.widget_checkbox, null, false)
            as MaterialCheckBox).apply { text = label }

    fun dialog(context: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(context)

    /** Plain field used by the hardened typed confirmation, which must stay dependency-light. */
    fun plainInput(context: Context, hint: CharSequence): EditText =
        EditText(context).apply {
            this.hint = hint
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
}
