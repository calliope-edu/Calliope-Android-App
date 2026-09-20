package cc.calliope.mini.ui.components

import android.icu.util.Calendar
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import cc.calliope.mini.ui.views.SnowfallView

/**
 * The holiday easter egg: a snowfall overlay on top of the activity's
 * content that reacts to shaking the device. Installs nothing outside the
 * season (Dec 6 — St. Nicholas Day — to Jan 10).
 */
object SnowfallDecorator {

    @JvmStatic
    fun install(activity: AppCompatActivity, root: ConstraintLayout) {
        if (!isHolidaySeason()) return
        val snowfall = SnowfallView(activity)
        root.addView(
            snowfall,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        ShakeDetector(activity, activity) { snowfall.shakeEffect() }
    }

    private fun isHolidaySeason(): Boolean {
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return (month == 12 && day >= 6) || (month == 1 && day <= 10)
    }
}
