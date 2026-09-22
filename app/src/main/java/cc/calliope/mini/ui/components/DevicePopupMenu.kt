package cc.calliope.mini.ui.components

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ListView
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import cc.calliope.mini.ui.popup.PopupAdapter
import cc.calliope.mini.ui.popup.PopupItem
import cc.calliope.mini.utils.Utils
import cc.calliope.mini.utils.WindowUtils
import kotlin.math.roundToInt

/**
 * The menu that drops out of the movable FAB. It opens towards the centre
 * of the screen from wherever the button currently sits, dims and blurs
 * the activity behind it, and tilts the button while open.
 */
class DevicePopupMenu(
    private val activity: Activity,
    private val onItemClick: (PopupItem) -> Unit,
) {
    private var popupWindow: PopupWindow? = null

    fun show(anchor: View, items: List<PopupItem>) {
        val parent = anchor.parent as? View
        val areaWidth = parent?.width?.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val areaHeight = parent?.height?.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val atStart = anchor.x.roundToInt() <= areaWidth / 2
        val atTop = anchor.y.roundToInt() <= areaHeight / 2

        val listView = ListView(activity).apply {
            adapter = PopupAdapter(activity, if (atStart) PopupAdapter.TYPE_START else PopupAdapter.TYPE_END, items)
            divider = null
            // Dispatch by item identity, not position: the item set changes
            // with the state (e.g. during a control session).
            setOnItemClickListener { parentView, _, position, _ ->
                popupWindow?.dismiss()
                (parentView.getItemAtPosition(position) as? PopupItem)?.let(onItemClick)
            }
        }

        // Size the window to its content; measured fresh each time, so a menu
        // with fewer items isn't sized for a past, longer one.
        var menuWidth = 0
        var menuHeight = 0
        for (i in 0 until listView.adapter.count) {
            val item = listView.adapter.getView(i, null, listView)
            item.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            menuWidth = maxOf(menuWidth, item.measuredWidth)
            menuHeight += item.measuredHeight
        }

        val gap = Utils.convertDpToPixel(activity, GAP_DP)
        val xOffset = if (atStart) gap else -(gap - anchor.width + menuWidth)
        val yOffset = if (atTop) gap else -(gap + anchor.height + menuHeight)

        popupWindow = PopupWindow(listView, menuWidth, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { decorate(anchor, open = false) }
            showAsDropDown(anchor, xOffset, yOffset)
        }
        decorate(anchor, open = true)
    }

    private fun decorate(anchor: View, open: Boolean) {
        val window = activity.window
        window.attributes = window.attributes.apply { alpha = if (open) DIMMED_ALPHA else 1.0f }
        WindowUtils.blurBehindDialog(activity, open)
        ViewCompat.animate(anchor)
            .rotation(if (open) 45.0f else 0.0f)
            .withLayer().setDuration(TILT_MS)
            .setInterpolator(OvershootInterpolator(10.0f))
            .start()
    }

    private companion object {
        const val GAP_DP = 4
        const val DIMMED_ALPHA = 0.5f
        const val TILT_MS = 300L
    }
}
