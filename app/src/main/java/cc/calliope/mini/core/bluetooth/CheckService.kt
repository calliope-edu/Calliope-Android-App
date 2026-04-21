package cc.calliope.mini.core.bluetooth

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import no.nordicsemi.android.kotlin.ble.core.scanner.BleNumOfMatches
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanFilter
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerCallbackType
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerMatchMode
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScannerSettings
import no.nordicsemi.android.kotlin.ble.scanner.BleScanner

@Suppress("MissingPermission")
class CheckService : Service() {
    private val job = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: BleScanner? = null
    private var scanJob: Job? = null
    private var statusCheckJob: Job? = null

    private var lastSeenTime: Long = 0
    private var isDeviceAvailable: Boolean = false
    private var macAddress: String = ""

    // Conditions for scanning
    private var isAppInForeground: Boolean = false
    private var isStateIdle: Boolean = true

    private val canScan: Boolean
        get() = isAppInForeground && isStateIdle && macAddress.isNotEmpty()

    private val stateObserver = Observer<State> { state ->
        val wasIdle = isStateIdle
        isStateIdle = state.type == State.STATE_IDLE || state.type == State.STATE_ERROR

        if (isStateIdle != wasIdle) {
            Log.d(TAG, "State changed: ${if (isStateIdle) "IDLE/ERROR" else "BUSY/FLASHING"}")
            updateScanningState()
        }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            Log.d(TAG, "App moved to foreground")
            isAppInForeground = true
            updateScanningState()
        }

        override fun onStop(owner: LifecycleOwner) {
            Log.d(TAG, "App moved to background")
            isAppInForeground = false
            updateScanningState()
        }
    }

    companion object {
        const val TAG = "CheckService"
        private var isRunning = false

        // Device considered unavailable if not seen for this duration
        const val DEVICE_TIMEOUT_MS = 10_000L
        // How often to check device availability status
        const val STATUS_CHECK_INTERVAL_MS = 3_000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            // Service already running — refresh scanning state in case lifecycle event was missed
            updateScanningState()
            return START_STICKY
        }

        isRunning = true
        Log.d(TAG, "Service started")

        // Get MAC address from preferences
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        macAddress = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""

        // Observe application state and lifecycle on main thread
        mainHandler.post {
            ApplicationStateHandler.getStateLiveData().observeForever(stateObserver)
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

            // Check current lifecycle state — if app is already in foreground
            // (e.g. service restarted by system after being killed), start scanning immediately
            val currentState = ProcessLifecycleOwner.get().lifecycle.currentState
            if (currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                Log.d(TAG, "App already in foreground on service start, triggering scan")
                isAppInForeground = true
                updateScanningState()
            }
        }

        // Start status check coroutine
        startStatusCheck()

        return START_STICKY
    }

    private fun updateScanningState() {
        // Re-read MAC address in case it changed
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        macAddress = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""

        Log.d(TAG, "updateScanningState: canScan=$canScan " +
                "(foreground=$isAppInForeground, idle=$isStateIdle, mac='$macAddress', scanJob=${scanJob != null})")

        if (canScan) {
            startScan()
        } else {
            stopScan()
        }
    }

    private fun startScan() {
        if (scanJob != null) {
            Log.d(TAG, "startScan: already scanning (scanJob active)")
            return
        }

        if (!Permission.isAccessGranted(applicationContext, *Permission.BLUETOOTH_PERMISSIONS)) {
            Log.e(TAG, "BLUETOOTH permission not granted")
            return
        }

        if (scanner == null) {
            scanner = BleScanner(applicationContext)
        }

        val filters = listOf(
            BleScanFilter(deviceAddress = macAddress)
        )

        val settings = BleScannerSettings(
            scanMode = BleScanMode.SCAN_MODE_LOW_POWER,
            reportDelay = 0L,
            legacy = false,
            callbackType = BleScannerCallbackType.CALLBACK_TYPE_ALL_MATCHES,
            numOfMatches = BleNumOfMatches.MATCH_NUM_MAX_ADVERTISEMENT,
            matchMode = BleScannerMatchMode.MATCH_MODE_AGGRESSIVE,
            includeStoredBondedDevices = false,
            phy = null
        )

        Log.d(TAG, "Starting scan for device: $macAddress")

        scanJob = scanner?.scan(filters, settings)
            ?.onEach {
                lastSeenTime = System.currentTimeMillis()
                if (!isDeviceAvailable) {
                    Log.d(TAG, "Device $macAddress is now available")
                    updateAvailability(true)
                }
            }
            ?.catch { e ->
                Log.e(TAG, "Scan error: ${e.message}")
            }
            ?.launchIn(serviceScope)
    }

    private fun stopScan() {
        if (scanJob == null) return // Not scanning

        scanJob?.cancel()
        scanJob = null
        lastSeenTime = 0
        updateAvailability(false)
        Log.d(TAG, "Scan stopped")
    }

    private fun startStatusCheck() {
        statusCheckJob = serviceScope.launch {
            while (true) {
                delay(STATUS_CHECK_INTERVAL_MS)
                if (canScan) {
                    checkDeviceTimeout()
                }
            }
        }
    }

    private fun checkDeviceTimeout() {
        if (isDeviceAvailable && lastSeenTime > 0) {
            val timeSinceLastSeen = System.currentTimeMillis() - lastSeenTime
            if (timeSinceLastSeen > DEVICE_TIMEOUT_MS) {
                Log.d(TAG, "Device not seen for ${timeSinceLastSeen}ms, marking as unavailable")
                updateAvailability(false)
            }
        }
    }

    private fun updateAvailability(available: Boolean) {
        if (isDeviceAvailable != available) {
            isDeviceAvailable = available
            ApplicationStateHandler.updateDeviceAvailability(available)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.post {
            ApplicationStateHandler.getStateLiveData().removeObserver(stateObserver)
            ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        }
        scanJob?.cancel()
        statusCheckJob?.cancel()
        job.cancel()
        isRunning = false
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}