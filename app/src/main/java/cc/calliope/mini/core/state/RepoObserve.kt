package cc.calliope.mini.core.state

import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Java-friendly collectors for [AppStateRepository] flows.
 *
 * Each helper collects while the owner is at least STARTED (matching
 * LiveData's active window) and delivers on the main thread. The returned
 * [Job] can usually be ignored — collection is lifecycle-bound — but lets
 * callers cancel early if they need to.
 */
object RepoObserve {

    private fun <T> collect(owner: LifecycleOwner, flow: Flow<T>, consumer: Consumer<T>): Job =
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { consumer.accept(it) }
            }
        }

    @JvmStatic
    fun mode(owner: LifecycleOwner, consumer: Consumer<AppMode>): Job =
        collect(owner, AppStateRepository.mode, consumer)

    @JvmStatic
    fun control(owner: LifecycleOwner, consumer: Consumer<Boolean>): Job =
        collect(owner, AppStateRepository.control, consumer)

    @JvmStatic
    fun flashEvents(owner: LifecycleOwner, consumer: Consumer<FlashEvent>): Job =
        collect(owner, AppStateRepository.flashEvents, consumer)

    @JvmStatic
    fun notifications(owner: LifecycleOwner, consumer: Consumer<Notification>): Job =
        collect(owner, AppStateRepository.notifications, consumer)

    @JvmStatic
    fun deviceAvailable(owner: LifecycleOwner, consumer: Consumer<Boolean>): Job =
        collect(owner, AppStateRepository.deviceAvailable, consumer)
}
