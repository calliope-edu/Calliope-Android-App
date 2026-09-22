package cc.calliope.mini.ui.components

import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.calliope.mini.R
import cc.calliope.mini.core.state.AppMode
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.FlashEvent
import cc.calliope.mini.core.state.FlashPhase
import cc.calliope.mini.ui.views.MovableFloatingActionButton
import kotlinx.coroutines.launch

/**
 * Renders the application state on the movable FAB: colour, the spinning
 * animation and the progress ring. This is the ONLY place that maps state
 * to a colour.
 *
 * Colour from (mode, control, board in range): a running flash or bonding
 * wins over a live editor session; the rest is idle/error, tinted by
 * whether the board is in range.
 */
class FabController(
    owner: LifecycleOwner,
    private val fab: MovableFloatingActionButton,
) : DefaultLifecycleObserver {

    private var mode: AppMode = AppMode.Idle
    private var controlActive = false
    private var deviceAvailable = false
    private var rotationAnimator: ObjectAnimator? = null

    init {
        fab.setPositionStore(PreferenceFabPositionStore(fab.context))
        owner.lifecycle.addObserver(this)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppStateRepository.mode.collect { onMode(it) } }
                launch { AppStateRepository.control.collect { controlActive = it; render() } }
                launch { AppStateRepository.deviceAvailable.collect { deviceAvailable = it; render() } }
                launch { AppStateRepository.flashEvents.collect { onFlashEvent(it) } }
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        // Progress events are one-shot; a ring left over from a flash that
        // ended while we were away must not stick.
        fab.setProgress(0)
    }

    private fun onMode(newMode: AppMode) {
        val wasSpinning = AppMode.isSpinning(mode)
        val spinning = AppMode.isSpinning(newMode)
        if (wasSpinning && !spinning) stopRotation() else if (!wasSpinning && spinning) startRotation()
        mode = newMode
        render()
    }

    private fun onFlashEvent(event: FlashEvent) {
        when (event) {
            is FlashEvent.Progress -> fab.setProgress(event.percent)
            is FlashEvent.Done -> fab.setProgress(0)
        }
    }

    private fun render() {
        val current = mode
        val color = when {
            current is AppMode.Flashing && current.phase >= FlashPhase.UPLOADING -> R.color.state_control
            AppMode.isSpinning(current) -> R.color.state_busy
            controlActive -> R.color.state_script
            current is AppMode.Error -> if (deviceAvailable) R.color.state_connected else R.color.status_error
            else -> if (deviceAvailable) R.color.state_connected else R.color.brand_accent
        }
        fab.setColor(color)
    }

    // Rotation while bonding and during the pre-upload flash phases
    private fun startRotation() {
        rotationAnimator = ObjectAnimator.ofFloat(fab, "rotation", 0f, 360f).apply {
            duration = ROTATION_PERIOD_MS
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        ObjectAnimator.ofFloat(fab, "rotation", fab.rotation, 0f).apply {
            duration = ROTATION_SETTLE_MS
            start()
        }
    }

    private companion object {
        const val ROTATION_PERIOD_MS = 2000L
        const val ROTATION_SETTLE_MS = 300L
    }
}
