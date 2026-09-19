package ai.arena.mobet.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] that stops growing past a fraction of the screen height.
 *
 * Mobet's report sheets hold arbitrarily long content (audit chains, diagnostics, dry-run
 * output). Without a cap the sheet would push its action buttons off-screen; with one the
 * body scrolls while the title and actions stay reachable.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** Fraction of the display height this view may occupy. */
    var maxHeightRatio: Float = 0.6f
        set(value) {
            field = value.coerceIn(0.2f, 0.95f)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (resources.displayMetrics.heightPixels * maxHeightRatio).toInt()
        val spec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, spec)
        if (measuredHeight > cap) {
            setMeasuredDimension(measuredWidth, cap)
        }
    }

    /** Convenience for callers that want the scroll position reset when content changes. */
    fun scrollToTop() = post { scrollTo(0, 0) }

    @Suppress("unused")
    fun contentHeight(): Int = getChildAt(0)?.let(View::getHeight) ?: 0
}
