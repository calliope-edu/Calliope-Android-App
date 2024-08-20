package cc.calliope.mini.core.bluetooth

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceMacAddress = intent?.getStringExtra("device_mac_address")
        val scanDuration = intent?.getLongExtra("scan_duration", 5000L) ?: 5000L // Значення за замовчуванням - 10 секунд
        val resultReceiver = getParcelableExtra(intent,"result_receiver")

        if (deviceMacAddress != null) {
            startScanning(deviceMacAddress, scanDuration, resultReceiver)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }


    private fun startScanning(macAddress: String, duration: Long, resultReceiver: ResultReceiver?) {
        val aggregator = BleScanResultAggregator()
        val scanner = BleScanner(applicationContext)
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

        serviceScope.launch {
            val scanJob = scanner.scan(filters, settings)
                .map { aggregator.aggregateDevices(it) }
                .onEach { devices ->
                    val device = devices.find { it.address == macAddress }
                    if (device != null) {
                        Log.d("BluetoothCheckService", "Device with MAC: $macAddress is available.")
                        resultReceiver?.send(RESULT_OK, null)
                        stopSelf()
                    }
                }
                .launchIn(this) // Запуск сканування

            // Завершення сервісу після закінчення часу сканування
            delay(duration)
            Log.d("BluetoothCheckService", "Device with MAC: $macAddress is not available.")
            resultReceiver?.send(RESULT_CANCELED, null)
            scanJob.cancel() // Зупиняємо сканування
            stopSelf() // Зупиняємо сервіс
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val RESULT_OK = 1
        const val RESULT_CANCELED = 0
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtra(intent: Intent?, name: String): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(name, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(name)
        }
    }
}