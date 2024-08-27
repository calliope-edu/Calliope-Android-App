package cc.calliope.mini.core.bluetooth

import android.app.Service
import android.content.Intent
import android.os.IBinder
import cc.calliope.mini.core.state.ScanResults
import cc.calliope.mini.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.core.scanner.BleNumOfMatches
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerCallbackType
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerMatchMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner

class ScanService : Service() {
    private val job = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    companion object {
        const val TAG = "CheckService"
        private var isRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Utils.log(TAG, "Service is already running. Ignoring new start request.")
            return START_NOT_STICKY
        }

        isRunning = true

        startScanning()

        return START_NOT_STICKY
    }

    @SuppressWarnings("MissingPermission")
    private fun startScanning() {
        val aggregator = Aggregator()
        val scanner = BleScanner(applicationContext)
        val filters = emptyList<BleScanFilter>()
        val settings = BleScannerSettings(
            BleScanMode.SCAN_MODE_BALANCED,
            10000L,
            false,
            BleScannerCallbackType.CALLBACK_TYPE_ALL_MATCHES,
            BleNumOfMatches.MATCH_NUM_MAX_ADVERTISEMENT,
            BleScannerMatchMode.MATCH_MODE_STICKY,
            false,
            null
        )

        serviceScope.launch {
            scanner.scan(filters, settings)
                .map { aggregator.aggregateDevices(it) }
                .onEach { devices ->
                    if (devices.isNotEmpty()) {
                        ScanResults().updateResults(devices)
                    }
                }
                .launchIn(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}