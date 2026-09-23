package ai.arena.mobet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

/**
 * Multiline code editor with a painted line-number gutter.
 *
 * The gutter completes the round-11 error-chip loop: the chip names "line 3, col 11" and the
 * editor should be able to *show* line 3. Numbers are drawn, never inserted into the text:
 * selection, copy/paste, draft persistence, Espresso replaceText, and the syntax highlighter
 * all operate on the exact same Editable as before, because nothing about the text model
 * changes. The gutter pads in from the XML-specified padding, so the background drawable and
 * insets trade exactly as before.
 *
 * Width adapts to the digit count of the current document (two digits minimum) and the gutter
 * is pinned against the view during horizontal scrolling, so long selector lines can't shove
 * the numbers off-screen.
 */
class LineNumberEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        // De-emphasized like a hint; the numbers frame the text, they are not the text.
    }

    /** The padding the layout asked for; the gutter adds to it, never replaces it. */
    private var basePaddingLeft = 0
    private var gutterDigits = MIN_DIGITS
    private var gutterWidthPx = 0

    init {
        gutterPaint.textSize = textSize
        gutterPaint.color = currentHintTextColor
        gutterPaint.typeface = typeface
        basePaddingLeft = paddingLeft
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateGutter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        // First width measurement needs the laid-out line count, so run once idle.
        post { updateGutter() }
    }

    /**
     * Resizes the gutter when the line count crosses a digit boundary (9 → 10 → 100 …).
     * setPadding does not emit text events, so this cannot recurse through the watcher.
     */
    private fun updateGutter() {
        val digits = maxOf(MIN_DIGITS, lineCount.coerceAtLeast(1).toString().length)
        if (digits == gutterDigits && paddingLeft == basePaddingLeft + gutterWidthPx) return
        gutterDigits = digits
        gutterWidthPx =
            gutterPaint.measureText(SAMPLE_DIGIT.repeat(digits)).toInt() + gutterMarginPx * 2
        val wanted = basePaddingLeft + gutterWidthPx
        if (paddingLeft != wanted) setPadding(wanted, paddingTop, paddingRight, paddingBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (textLayout != null && gutterWidthPx > 0) {
            val numberRightEdge = (paddingLeft - gutterMarginPx).toFloat()
            canvas.save()
            // Pin against horizontal scroll; vertical scroll is already in the canvas matrix.
            canvas.translate(scrollX.toFloat(), 0f)
            for (line in 0 until textLayout.lineCount) {
                val baseline = paddingTop + textLayout.getLineBaseline(line).toFloat()
                canvas.drawText((line + 1).toString(), numberRightEdge, baseline, gutterPaint)
            }
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    private val gutterMarginPx: Int
        get() = (resources.displayMetrics.density * GUTTER_MARGIN_DP).toInt()

    private companion object {
        /** "9" is the widest digit in a typical monospace-adjacent sans; measure with it. */
        const val SAMPLE_DIGIT = "9"
        const val MIN_DIGITS = 2
        const val GUTTER_MARGIN_DP = 8
    }
}
