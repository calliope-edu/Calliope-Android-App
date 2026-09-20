package cc.calliope.mini.ui.components

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.ui.SnackbarHelper
import kotlinx.coroutines.launch

/**
 * Shows the application's notifications as snackbars while the activity
 * is started.
 */
class SnackbarPresenter(
    private val activity: AppCompatActivity,
    private val root: () -> View?,
) {
    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppStateRepository.notifications.collect { show(it) }
            }
        }
    }

    /**
     * The view a snackbar should attach to: the content of a showing dialog
     * (scripts bottom sheet, pattern dialog) if there is one, else the
     * activity root. A dialog is its own window drawn above the activity —
     * and the activity content is blurred behind it — so a snackbar in the
     * activity window ends up blurred or fully covered by the sheet.
     */
    fun host(): View? {
        for (fragment in activity.supportFragmentManager.fragments) {
            val dialog = (fragment as? DialogFragment)?.dialog ?: continue
            if (!dialog.isShowing) continue
            dialog.findViewById<View>(android.R.id.content)?.let { return it }
        }
        return root()
    }

    private fun show(notification: Notification) {
        val host = host() ?: return
        val message = notification.message ?: return
        when (notification.type) {
            Notification.INFO -> SnackbarHelper.infoSnackbar(host, message).show()
            Notification.WARNING -> SnackbarHelper.warningSnackbar(host, message).show()
            Notification.ERROR -> SnackbarHelper.errorSnackbar(host, message).show()
        }
    }
}
