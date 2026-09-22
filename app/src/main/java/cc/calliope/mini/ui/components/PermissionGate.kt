package cc.calliope.mini.ui.components

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import cc.calliope.mini.R
import cc.calliope.mini.ui.SnackbarHelper
import cc.calliope.mini.ui.activity.NoPermissionActivity
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.Utils
import com.google.android.material.snackbar.BaseTransientBottomBar

/**
 * Keeps the user out of the app's screens until it may use Bluetooth: on
 * every resume, missing runtime permissions send them to
 * [NoPermissionActivity]; with permissions in place, a disabled Bluetooth
 * (or, before Android 12, disabled location) is pointed out in a snackbar.
 *
 * This is the one permission gate of the app — the screens behind it (the
 * editors included) can assume [Permission.isBleAccessGranted].
 *
 * Construct it as a field of the activity: the result launcher has to be
 * registered before the activity starts.
 */
class PermissionGate(
    private val activity: AppCompatActivity,
    private val snackbarHost: () -> View?,
) : DefaultLifecycleObserver {

    private val enableBluetooth = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* the next onResume re-checks */ }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            Permission.isAccessGranted(activity, *Permission.POST_NOTIFICATIONS)
        if (!Permission.isBleAccessGranted(activity) || !notificationsGranted) {
            activity.startActivity(Intent(activity, NoPermissionActivity::class.java))
            return
        }
        if (!Utils.isBluetoothEnabled(activity)) {
            showBluetoothDisabledWarning()
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !Utils.isLocationEnabled(activity)) {
            val host = snackbarHost() ?: return
            SnackbarHelper.errorSnackbar(host, activity.getString(R.string.error_snackbar_location_disable)).show()
        }
    }

    /** True if Bluetooth is on; otherwise explains it, with an "enable" action. */
    fun requireBluetoothEnabled(): Boolean {
        if (Utils.isBluetoothEnabled(activity)) return true
        showBluetoothDisabledWarning()
        return false
    }

    private fun showBluetoothDisabledWarning() {
        val host = snackbarHost() ?: return
        SnackbarHelper.errorSnackbar(host, activity.getString(R.string.error_snackbar_bluetooth_disabled))
            .setDuration(BaseTransientBottomBar.LENGTH_INDEFINITE)
            .setAction(R.string.button_enable) {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            .show()
    }
}
