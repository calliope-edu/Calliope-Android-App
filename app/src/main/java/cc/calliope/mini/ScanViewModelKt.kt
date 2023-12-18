package cc.calliope.mini

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.kotlin.ble.core.scanner.BleNumOfMatches
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResults
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerCallbackType
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerMatchMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner

class ScanViewModelKt(application: Application) : AndroidViewModel(application) {

    private val _devices = MutableLiveData<List<BleScanResults>>()
    val devices: LiveData<List<BleScanResults>> get() = _devices

    fun startScan() {
        val context = getApplication<Application>().applicationContext
        val settings = BleScannerSettings(
            BleScanMode.SCAN_MODE_BALANCED,
            0L,
            BleScannerCallbackType.CALLBACK_TYPE_ALL_MATCHES,
            BleNumOfMatches.MATCH_NUM_MAX_ADVERTISEMENT,
            BleScannerMatchMode.MATCH_MODE_AGGRESSIVE,
            false,
            null
        )
        //Create aggregator which will concat scan records with a device
        val aggregator = Aggregator()

        BleScanner(context).scan(settings)
            .map { aggregator.aggregate(it) }// Add new device and return an aggregated list
            .onEach { _devices.value = it } // Propagated state to UI
            .launchIn(viewModelScope) // Scanning will stop after we leave the screen
    }

    fun stopScan() {
        viewModelScope.cancel()
    }
}

