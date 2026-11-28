package app.tether.client.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

internal const val CLIENT_TAB_MAX_WIDTH = 520

/** Shared content frame for Messages, Contacts, Keypad and Recents, inside the inset-aware Activity. */
internal open class ClientTabViewport(context: Context) : FrameLayout(context) {
    val canvas = FrameLayout(context)

    init {
        addView(canvas, LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        canvas.layoutParams.width = minOf(MeasureSpec.getSize(widthMeasureSpec), context.dp(CLIENT_TAB_MAX_WIDTH))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun column() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        setPaddingRelative(context.dp(16), context.dp(8), context.dp(16), 0)
    }
}
