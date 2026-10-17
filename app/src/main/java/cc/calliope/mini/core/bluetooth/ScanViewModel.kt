package cc.calliope.mini.core.bluetooth

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.kotlin.ble.core.scanner.BleNumOfMatches
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerCallbackType
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerMatchMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner


class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _devices = MutableLiveData<List<Device>>()
    val devices: LiveData<List<Device>> get() = _devices

    private var scanJob: Job? = null

    @SuppressWarnings("MissingPermission")
    fun startScan() {
        if (scanJob?.isActive == true) return
        val context = getApplication<Application>().applicationContext
        val filters = emptyList<BleScanFilter>()
        val settings = BleScannerSettings(
            BleScanMode.SCAN_MODE_BALANCED,
            0L,
            false,
            BleScannerCallbackType.CALLBACK_TYPE_ALL_MATCHES,
            BleNumOfMatches.MATCH_NUM_MAX_ADVERTISEMENT,
            BleScannerMatchMode.MATCH_MODE_AGGRESSIVE,
            false,
            null
        )

        //Create aggregator which will concat scan records with a device
        val aggregator = Aggregator()

        scanJob = BleScanner(context).scan(filters, settings)
            .map { aggregator.aggregateDevices(it) }// Add new device and return an aggregated list
            .onEach { _devices.value = it } // Propagated state to UI
            .catch { exception ->
                Log.e("BleScan", "Error during BLE scan or data aggregation", exception)
            }
            .launchIn(viewModelScope) // Scanning will stop after we leave the screen
    }

    /**
     * Stops this scan only. Cancelling viewModelScope here (as it used to)
     * killed the scope for good, so a later startScan() silently did nothing.
     */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }
}

