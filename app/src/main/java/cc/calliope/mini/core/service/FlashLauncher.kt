package cc.calliope.mini.core.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import cc.calliope.mini.R
import cc.calliope.mini.core.bluetooth.BoardGeneration
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.ui.activity.FlashingActivity
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Utils
import cc.calliope.mini.utils.settings.Settings

/**
 * The one way to start a flash. Every entry point — an editor download,
 * the programs list, an opened hex file, Retry, the campus bridge — used
 * to carry its own copy of "Bluetooth on? board in range? show the
 * flashing screen? start the service", and the copies disagreed.
 *
 * The launcher also resolves the target (address, name, generation of the
 * current board) and hands it to [FlashingService] in the intent, so the
 * service no longer reaches into SharedPreferences for it.
 */
object FlashLauncher {
    private const val TAG = "FlashLauncher"

    const val EXTRA_FILE_PATH = Constants.EXTRA_FILE_PATH
    const val EXTRA_DEVICE_ADDRESS = "cc.calliope.mini.EXTRA_FLASH_DEVICE_ADDRESS"
    const val EXTRA_DEVICE_NAME = "cc.calliope.mini.EXTRA_FLASH_DEVICE_NAME"
    const val EXTRA_BOARD_GENERATION = "cc.calliope.mini.EXTRA_FLASH_BOARD_GENERATION"
    const val EXTRA_FORCE_FULL_DFU = "extra_force_full_dfu"

    /** Whether the launcher opens the flashing screen. */
    enum class Screen {
        /** Open it unless the user chose background flashing in Settings. */
        FOLLOW_SETTINGS,
        /** Never — the caller renders progress itself (campus widget). */
        NONE,
    }

    /** [error] is the user-facing reason when [started] is false (already shown as a snackbar). */
    class Result(@JvmField val started: Boolean, @JvmField val error: String?)

    @JvmStatic
    @JvmOverloads
    fun launch(
        context: Context,
        filePath: String,
        screen: Screen = Screen.FOLLOW_SETTINGS,
        forceFullDfu: Boolean = false,
        /**
         * Require the board to be seen by the background scan. False for
         * callers that hold the link themselves until this moment: the scan
         * is paused during an editor session, so "not seen" means nothing.
         */
        requireInRange: Boolean = true,
    ): Result {
        if (!Utils.isBluetoothEnabled(context)) {
            return refuse(context, R.string.error_snackbar_bluetooth_disabled)
        }
        if (requireInRange && !AppStateRepository.deviceAvailable.value) {
            return refuse(context, R.string.error_no_connected)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Remembered for Retry only; the service gets the path in the intent.
        prefs.edit().putString(Constants.CURRENT_FILE_PATH, filePath).apply()

        val service = Intent(context, FlashingService::class.java)
            .putExtra(EXTRA_FILE_PATH, filePath)
            .putExtra(EXTRA_DEVICE_ADDRESS, prefs.getString(Constants.CURRENT_DEVICE_ADDRESS, ""))
            .putExtra(EXTRA_DEVICE_NAME, prefs.getString(Constants.CURRENT_DEVICE_PATTERN, ""))
            .putExtra(EXTRA_BOARD_GENERATION, BoardGeneration.current(context).prefValue)
            .putExtra(EXTRA_FORCE_FULL_DFU, forceFullDfu)

        if (screen == Screen.FOLLOW_SETTINGS && !Settings.isBackgroundFlashingEnable(context)) {
            val activity = Intent(context, FlashingActivity::class.java)
            if (context !is android.app.Activity) activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(activity)
        }

        return try {
            context.startService(service)
            Result(true, null)
        } catch (e: Exception) {
            // e.g. background-start restrictions
            Log.e(TAG, "could not start FlashingService", e)
            Result(false, "could not start flashing service: ${e.message}")
        }
    }

    /** Flash the last file again, to the board that is current now. */
    @JvmStatic
    fun retry(context: Context): Result {
        val path = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Constants.CURRENT_FILE_PATH, "").orEmpty()
        if (path.isEmpty()) return refuse(context, R.string.error_file_path_missing)
        // The flashing screen is where Retry lives — it is already open.
        return launch(context, path, Screen.NONE)
    }

    private fun refuse(context: Context, messageRes: Int): Result {
        val message = context.getString(messageRes)
        AppStateRepository.updateNotification(Notification.ERROR, message)
        return Result(false, message)
    }
}
