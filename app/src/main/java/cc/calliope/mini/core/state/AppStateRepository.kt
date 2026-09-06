package cc.calliope.mini.core.state

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-scoped application state.
 *
 * Channel semantics are deliberate:
 *
 *  - [mode], [control] and [deviceAvailable] are STICKY ([StateFlow]): a
 *    late subscriber (a recreated activity, the FAB) must see the current
 *    value immediately. An error is part of [mode] ([AppMode.Error]), so a
 *    reader can never pair a state with a stale error.
 *  - [flashEvents] and [notifications] are ONE-SHOT ([SharedFlow] with
 *    replay = 0): a re-subscribing screen must NOT receive a stale
 *    `Done` or re-show an old snackbar.
 *
 * Transitions are owned here. A flash is a session: [beginFlash] is the
 * process-wide mutex, [finishFlash] is its single terminal call, and the
 * phase/progress reporters are ignored (and logged) when no flash is
 * running — so a late callback from a previous flash cannot corrupt the
 * next one.
 *
 * All mutators are safe to call from any thread ([MutableStateFlow.value]
 * is atomic; the shared flows use `tryEmit` with a drop-oldest buffer so
 * they never block a GATT binder thread or an IO worker).
 */
object AppStateRepository {
    private const val TAG = "AppStateRepository"

    @Volatile
    private lateinit var appContext: Context

    /** Call once from [cc.calliope.mini.App.onCreate]. */
    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    // ---- Sticky state -----------------------------------------------------

    private val _mode = MutableStateFlow<AppMode>(AppMode.Idle)
    @JvmStatic
    val mode: StateFlow<AppMode> get() = _mode.asStateFlow()

    /** True while a web editor holds a live BLE session with the board. */
    private val _control = MutableStateFlow(false)
    @JvmStatic
    val control: StateFlow<Boolean> get() = _control.asStateFlow()

    private val _deviceAvailable = MutableStateFlow(false)
    @JvmStatic
    val deviceAvailable: StateFlow<Boolean> get() = _deviceAvailable.asStateFlow()

    // ---- One-shot events --------------------------------------------------

    private val _flashEvents = MutableSharedFlow<FlashEvent>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @JvmStatic
    val flashEvents: SharedFlow<FlashEvent> get() = _flashEvents.asSharedFlow()

    private val _notifications = MutableSharedFlow<Notification>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @JvmStatic
    val notifications: SharedFlow<Notification> get() = _notifications.asSharedFlow()

    // ---- Non-flash work (bonding, USB copy) --------------------------------

    /** Enter [AppMode.Busy]. Refused while a flash is running. */
    @JvmStatic
    fun setBusy() {
        _mode.update { current ->
            if (current is AppMode.Flashing) {
                Log.w(TAG, "setBusy ignored: flash in progress ($current)")
                current
            } else {
                AppMode.Busy
            }
        }
    }

    /** Back to [AppMode.Idle]. Refused while a flash is running. */
    @JvmStatic
    fun setIdle() {
        _mode.update { current ->
            if (current is AppMode.Flashing) {
                Log.w(TAG, "setIdle ignored: flash in progress ($current)")
                current
            } else {
                AppMode.Idle
            }
        }
    }

    /** Non-flash failure. Refused while a flash is running (use [finishFlash]). */
    @JvmStatic
    fun setError(message: String?) {
        _mode.update { current ->
            if (current is AppMode.Flashing) {
                Log.w(TAG, "setError ignored: flash in progress ($current)")
                current
            } else {
                AppMode.Error(AppMode.Error.NO_CODE, message)
            }
        }
    }

    /** How to end the current control session, registered by its owner. */
    @Volatile
    private var controlDisconnect: (() -> Unit)? = null

    @JvmStatic
    fun setControl(active: Boolean) = setControl(active, null)

    /**
     * Flag a live editor session. The owner passes [disconnect] so the UI
     * (the FAB menu) can end the session without knowing which editor —
     * Cardboard UART, Scratch Link or the campus bridge — holds it.
     */
    @JvmStatic
    fun setControl(active: Boolean, disconnect: (() -> Unit)?) {
        controlDisconnect = if (active) disconnect else null
        _control.value = active
    }

    /** End the current control session. Returns false if none is registered. */
    @JvmStatic
    fun disconnectControl(): Boolean {
        val action = controlDisconnect ?: return false
        action()
        return true
    }

    @JvmStatic
    fun updateDeviceAvailability(isAvailable: Boolean) {
        _deviceAvailable.value = isAvailable
    }

    // ---- Flash session ----------------------------------------------------

    /**
     * Claim the flash mutex. Returns false — and changes nothing — if a
     * flash is already running. A stale [AppMode.Busy] does not block a
     * flash (it would otherwise wedge the app until process restart).
     */
    @JvmStatic
    fun beginFlash(mode: FlashMode): Boolean {
        var claimed = false
        _mode.update { current ->
            if (current is AppMode.Flashing) {
                claimed = false
                current
            } else {
                claimed = true
                AppMode.Flashing(FlashPhase.PREPARING, mode)
            }
        }
        if (!claimed) Log.w(TAG, "beginFlash refused: flash already in progress")
        return claimed
    }

    @JvmStatic
    fun flashPhase(phase: FlashPhase) {
        _mode.update { current ->
            if (current is AppMode.Flashing) current.copy(phase = phase)
            else ignored("flashPhase($phase)", current)
        }
    }

    /** Partial → full DFU fallback flips the mode mid-session. */
    @JvmStatic
    fun flashMode(mode: FlashMode) {
        _mode.update { current ->
            if (current is AppMode.Flashing) current.copy(mode = mode)
            else ignored("flashMode($mode)", current)
        }
    }

    @JvmStatic
    fun flashProgress(percent: Int) {
        if (_mode.value !is AppMode.Flashing) {
            ignored("flashProgress($percent)", _mode.value)
            return
        }
        _flashEvents.tryEmit(FlashEvent.Progress(percent.coerceIn(0, 100)))
    }

    /**
     * The single terminal call of a flash session: releases the mutex,
     * publishes the sticky outcome in [mode], then emits [FlashEvent.Done].
     * Ignored if no flash is running.
     */
    @JvmStatic
    fun finishFlash(result: FlashResult) {
        var finished = false
        _mode.update { current ->
            if (current is AppMode.Flashing) {
                finished = true
                when (result) {
                    is FlashResult.Success -> AppMode.Idle
                    is FlashResult.Failure -> AppMode.Error(result.code, result.message)
                }
            } else {
                ignored("finishFlash($result)", current)
            }
        }
        if (finished) _flashEvents.tryEmit(FlashEvent.Done(result))
    }

    private fun ignored(call: String, current: AppMode): AppMode {
        Log.w(TAG, "$call ignored: no flash in progress (mode=$current)")
        return current
    }

    // ---- Notifications ----------------------------------------------------

    @JvmStatic
    fun updateNotification(@Notification.NotificationType type: Int, message: String) {
        _notifications.tryEmit(Notification(type, message))
    }

    @JvmStatic
    fun updateNotification(@Notification.NotificationType type: Int, stringId: Int) {
        _notifications.tryEmit(Notification(type, appContext.getString(stringId)))
    }
}
