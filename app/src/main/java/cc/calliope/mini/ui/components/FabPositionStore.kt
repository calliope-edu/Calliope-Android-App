package cc.calliope.mini.ui.components

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Where the movable FAB remembers its place, as fractions of the parent's
 * size so it survives rotation and different screens. Kept out of the View
 * so the button does no storage I/O of its own.
 */
interface FabPositionStore {
    /** The saved (xFraction, yFraction), or null if the user never moved the button. */
    fun load(): Pair<Float, Float>?

    fun save(xFraction: Float, yFraction: Float)
}

class PreferenceFabPositionStore(context: Context) : FabPositionStore {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    override fun load(): Pair<Float, Float>? {
        if (!prefs.contains(KEY_X)) return null
        return prefs.getFloat(KEY_X, 0f) to prefs.getFloat(KEY_Y, 0f)
    }

    override fun save(xFraction: Float, yFraction: Float) {
        prefs.edit().putFloat(KEY_X, xFraction).putFloat(KEY_Y, yFraction).apply()
    }

    private companion object {
        // Keys predate this class — keep them so saved positions survive the update.
        const val KEY_X = "fab_x_fraction"
        const val KEY_Y = "fab_y_fraction"
    }
}
