package cc.calliope.mini.core.bluetooth

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import cc.calliope.mini.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import no.nordicsemi.android.kotlin.ble.scanner.aggregator.BleScanResultAggregator

@SuppressWarnings("MissingPermission")
class CheckService : Service() {
    private val job = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    companion object {
        const val TAG = "CheckService"
        const val RESULT_OK = 1
        const val RESULT_CANCELED = 0
        private var isRunning = false
        const val SCAN_DURATION = 8000L // Duration of scanning in milliseconds
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Utils.log(TAG, "Service is already running. Ignoring new start request.")
            return START_NOT_STICKY
        }

        isRunning = true

        val deviceMacAddress = intent?.getStringExtra("device_mac_address")
        val scanDuration = intent?.getLongExtra("scan_duration", SCAN_DURATION) ?: SCAN_DURATION
        val resultReceiver = getResultReceiver(intent)

        if (!deviceMacAddress.isNullOrEmpty()) {
            startScanning(deviceMacAddress, scanDuration, resultReceiver)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startScanning(macAddress: String, duration: Long, resultReceiver: ResultReceiver?) {
        val aggregator = BleScanResultAggregator()
        val scanner = BleScanner(applicationContext)
        val filters = listOf(
            BleScanFilter(
                null,
                null,
                macAddress,
                null,
                null,
                null,
                null,
                null
            )
        )
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

        serviceScope.launch {
            val scanJob = scanner.scan(filters, settings)
                .map { aggregator.aggregateDevices(it) }
                .onEach { devices ->
                    if (devices.isNotEmpty()) {
                        Utils.log(TAG, "Device with MAC: $macAddress is available.")
                        resultReceiver?.send(RESULT_OK, null)
                        stopSelf()
                    }
                }
                .launchIn(this)

            // Stop scanning after the specified duration
            delay(duration)
            Utils.log(TAG, "Device with MAC: $macAddress is not available.")
            resultReceiver?.send(RESULT_CANCELED, null)
            scanJob.cancel() // Stop scanning
            stopSelf() // Stop the service
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

    @Suppress("DEPRECATION")
    private fun getResultReceiver(intent: Intent?): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("result_receiver", ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra("result_receiver")
        }
    }
}