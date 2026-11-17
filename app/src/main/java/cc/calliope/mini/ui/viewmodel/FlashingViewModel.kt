package cc.calliope.mini.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.FlashEvent
import cc.calliope.mini.core.state.FlashResult
import kotlinx.coroutines.launch

/**
 * Keeps the flashing screen's one-shot facts across a rotation. The phase
 * and an error are sticky in [AppStateRepository.mode] and need no help,
 * but upload progress and "it completed" are events: a recreated activity
 * would show an empty percentage until the next tick, and would never
 * learn that the flash finished while it was being recreated.
 */
class FlashingViewModel : ViewModel() {

    private val _percent = MutableLiveData<Int?>(null)
    /** Last upload progress of the running flash, null before the first tick. */
    val percent: LiveData<Int?> = _percent

    private val _completed = MutableLiveData(false)
    /** True once the flash this screen is showing ended successfully. */
    val completed: LiveData<Boolean> = _completed

    init {
        viewModelScope.launch {
            AppStateRepository.flashEvents.collect { event ->
                when (event) {
                    is FlashEvent.Progress -> _percent.value = event.percent
                    is FlashEvent.Done -> if (event.result is FlashResult.Success) _completed.value = true
                }
            }
        }
    }

    /** A new flash starts on this screen (Retry). */
    fun reset() {
        _percent.value = null
        _completed.value = false
    }
}
