package cc.calliope.mini.core.state

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped application state (phase 2 replacement for the static
 * LiveData bus in [ApplicationStateHandler]).
 *
 * Channel semantics are deliberate — they encode the split the old bus
 * lacked and that every downstream hack compensated for:
 *
 *  - [state], [error], [deviceAvailable] are STICKY ([StateFlow]): late
 *    subscribers (a recreated activity, the FAB) must see the current value
 *    immediately. `null` mirrors the old "no value yet" of bare LiveData.
 *  - [progress] and [notifications] are ONE-SHOT ([SharedFlow] with
 *    replay = 0): a re-subscribing screen must NOT receive a stale
 *    `PROGRESS_COMPLETED` or re-show an old snackbar. This removes the
 *    `Event<T>` wrapper and the `flashingStarted` stale-replay guards.
 *
 * All mutators are safe to call from any thread ([MutableStateFlow.value]
 * is atomic; the shared flows use `tryEmit` with a drop-oldest buffer so
 * they never block a GATT binder thread or an IO worker).
 *
 * During the migration [ApplicationStateHandler] dual-writes into this
 * repository; once every reader collects from here, the writers move over
 * and the old handler is deleted.
 */
object AppStateRepository {

    @Volatile
    private lateinit var appContext: Context

    /** Call once from [cc.calliope.mini.App.onCreate]. */
    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    // ---- Sticky state -----------------------------------------------------

    private val _state = MutableStateFlow<State?>(null)
    @JvmStatic
    val state: StateFlow<State?> get() = _state.asStateFlow()

    private val _error = MutableStateFlow<Error?>(null)
    @JvmStatic
    val error: StateFlow<Error?> get() = _error.asStateFlow()

    private val _deviceAvailable = MutableStateFlow(false)
    @JvmStatic
    val deviceAvailable: StateFlow<Boolean> get() = _deviceAvailable.asStateFlow()

    // ---- One-shot events --------------------------------------------------

    private val _progress = MutableSharedFlow<Progress>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @JvmStatic
    val progress: SharedFlow<Progress> get() = _progress.asSharedFlow()

    private val _notifications = MutableSharedFlow<Notification>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    @JvmStatic
    val notifications: SharedFlow<Notification> get() = _notifications.asSharedFlow()

    // ---- Mutators (mirror the old handler API during the migration) -------

    @JvmStatic
    fun updateState(@State.StateType type: Int) {
        _state.value = State(type)
    }

    @JvmStatic
    fun updateNotification(@Notification.NotificationType type: Int, message: String) {
        _notifications.tryEmit(Notification(type, message))
    }

    @JvmStatic
    fun updateNotification(@Notification.NotificationType type: Int, stringId: Int) {
        _notifications.tryEmit(Notification(type, appContext.getString(stringId)))
    }

    @JvmStatic
    fun updateProgress(percent: Int) {
        _progress.tryEmit(Progress(percent))
    }

    @JvmStatic
    fun updateError(code: Int, message: String?) {
        _error.value = Error(code, message)
    }

    @JvmStatic
    fun updateDeviceAvailability(isAvailable: Boolean) {
        _deviceAvailable.value = isAvailable
    }
}
