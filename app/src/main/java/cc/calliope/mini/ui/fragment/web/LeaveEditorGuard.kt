package cc.calliope.mini.ui.fragment.web

import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import cc.calliope.mini.R
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.ui.dialog.DialogUtils

/**
 * Asks before Back leaves an editor that holds a live BLE session — only
 * then; without a session (MakeCode, Python, a Cardboard page that never
 * connected) Back just leaves.
 *
 * [canKeep]: the page survives leaving (Blocks, retained), so the choice is
 * "keep it running in the background" or "close". A page torn down on exit
 * (Cardboard, campus) loses the session either way, so the choice there is
 * "stay" or "close".
 */
object LeaveEditorGuard {

    fun install(fragment: Fragment, canKeep: Boolean, close: () -> Unit) {
        val dispatcher = fragment.requireActivity().onBackPressedDispatcher
        dispatcher.addCallback(fragment.viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val leave = {
                    // Route this Back past us, once.
                    isEnabled = false
                    dispatcher.onBackPressed()
                }
                if (!AppStateRepository.control.value) {
                    leave()
                    return
                }
                val context = fragment.requireContext()
                if (canKeep) {
                    DialogUtils.showChoiceDialog(
                        context,
                        context.getString(R.string.title_dialog_leave_editor),
                        context.getString(R.string.info_dialog_leave_editor_keep),
                        context.getString(R.string.button_keep_editor), { leave() },
                        context.getString(R.string.button_close_editor), { close(); leave() },
                    )
                } else {
                    DialogUtils.showChoiceDialog(
                        context,
                        context.getString(R.string.title_dialog_leave_editor),
                        context.getString(R.string.info_dialog_leave_editor_close),
                        context.getString(R.string.button_stay_editor), { },
                        context.getString(R.string.button_close_editor), { close(); leave() },
                    )
                }
            }
        })
    }
}
