package cc.calliope.mini.core.bluetooth

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import androidx.preference.PreferenceManager
import cc.calliope.mini.core.service.LegacyDfuService
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
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

@Suppress("MissingPermission")
class CheckService : Service() {
    private val job = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)

    companion object {
        const val TAG = "CheckService"
        const val RESULT_OK = 1
        const val RESULT_CANCELED = 0
        private var isRunning = false
        const val SCAN_DURATION = 8000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            Log.w(TAG, "Service is already running. Ignoring new start request.")
            return START_NOT_STICKY
        }

        isRunning = true

        val scanDuration = intent?.getLongExtra("scan_duration", SCAN_DURATION) ?: SCAN_DURATION
        val resultReceiver = getResultReceiver(intent)

        startScanning(scanDuration, resultReceiver)

        return START_NOT_STICKY
    }

    private fun startScanning(duration: Long, resultReceiver: ResultReceiver?) {
        val aggregator = BleScanResultAggregator()
        val scanner = BleScanner(applicationContext)

        serviceScope.launch {
            while (true) {
                val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                val macAddress = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""

                if (!Permission.isAccessGranted(applicationContext, *Permission.BLUETOOTH_PERMISSIONS)) {
                    Log.e(LegacyDfuService.TAG, "BLUETOOTH permission no granted")
                    stopSelf()
                    break
                }

                if (macAddress.isEmpty()) {
                    Log.w(TAG, "No MAC address found. Stopping service.")
                    resultReceiver?.send(RESULT_CANCELED, null)
                    stopSelf()
                    break
                }

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

                val scanJob = scanner.scan(filters, settings)
                    .map { aggregator.aggregateDevices(it) }
                    .onEach { devices ->
                        if (devices.isNotEmpty()) {
                            Log.d(TAG, "Device with MAC: $macAddress is available.")
                            resultReceiver?.send(RESULT_OK, null)
                            stopSelf()
                        }
                    }
                    .launchIn(this)

                delay(duration)

                Log.w(TAG, "Device with MAC: $macAddress not found after $duration ms.")
                scanJob.cancel()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun getResultReceiver(intent: Intent?): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("result_receiver", ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra("result_receiver")
        }
    }
}