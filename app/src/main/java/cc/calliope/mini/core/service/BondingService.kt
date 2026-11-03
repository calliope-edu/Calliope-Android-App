package cc.calliope.mini.core.service

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import cc.calliope.mini.R
import cc.calliope.mini.core.bluetooth.BleUuids
import cc.calliope.mini.core.bluetooth.BoardGeneration
import cc.calliope.mini.core.bluetooth.GattConnection
import cc.calliope.mini.core.bluetooth.GattStatus
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import java.util.UUID

/**
 * "Connect" from the pattern dialog: identify the board generation and
 * make sure it is bonded.
 *
 * The script (hardware contract 4 — keep it):
 *  - connect with a 2 s settle before discovery, cache refreshed;
 *  - `E95D93B0` present ⇒ nRF51 (mini 1/2): READ `E95D93B1`, which makes
 *    the board request pairing; then disconnect;
 *  - otherwise `FE59` present ⇒ nRF52 (mini 3): `createBond()` if not
 *    bonded, then disconnect;
 *  - neither ⇒ failure, no reconnect.
 *
 * A link the board drops (status 19 / 8) or a failed connect is retried
 * [numbAttempts] times. The outcome is published explicitly by [finish]:
 * app mode back to Idle with the "connected" notification and the saved
 * board version, or Error with the reason.
 */
class BondingService : Service(), GattConnection.Listener {

    companion object {
        const val TAG = "BondingService"
        const val EXTRA_DEVICE_ADDRESS = Constants.CURRENT_DEVICE_ADDRESS
        const val EXTRA_NUMB_ATTEMPTS = Constants.EXTRA_NUMB_ATTEMPTS
        const val DEFAULT_NUMB_ATTEMPTS = 2

        /** Settle time before the first connect (contract 4). */
        const val CONNECT_DELAY_MS = 2000L
        /** Settle time between connect and service discovery (contract 4). */
        const val DISCOVERY_DELAY_MS = 2000L
        /** Before a reconnect: the old 2 s reconnect pause plus the 2 s connect settle. */
        const val RECONNECT_DELAY_MS = 4000L
        const val CONNECT_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var connection: GattConnection? = null
    private var device: BluetoothDevice? = null
    private var numbAttempts = DEFAULT_NUMB_ATTEMPTS
    /** Failed connects / errors. */
    private var attempts = 0
    /** Links the board dropped itself. */
    private var deviceDisconnects = 0
    private var generation = BoardGeneration.UNKNOWN
    /** Set by [fail]; a non-zero disconnect after it ends the service. */
    private var failed = false
    private var finished = false

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Bonding Service started")

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
            Log.e(TAG, "Bluetooth permissions not granted")
            fail(R.string.error_bluetooth_permissions)
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (intent == null || address == null) {
            Log.e(TAG, "Intent or device address is null, stopping service")
            fail(R.string.error_connection_failed)
            return START_NOT_STICKY
        }
        numbAttempts = intent.getIntExtra(EXTRA_NUMB_ATTEMPTS, DEFAULT_NUMB_ATTEMPTS)

        val adapter = BluetoothUtils.getAdapter(this)
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            fail(R.string.error_bluetooth_not_enabled)
            return START_NOT_STICKY
        }
        if (!BluetoothUtils.isValidBluetoothMAC(address)) {
            fail(R.string.error_invalid_mac_address)
            return START_NOT_STICKY
        }
        device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) {
            Log.e(TAG, "Device is null")
            fail(R.string.error_device_null)
            return START_NOT_STICKY
        }

        handler.postDelayed({ connect() }, CONNECT_DELAY_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        connection?.disconnect()
        connection = null
        Log.d(TAG, "Bonding Service destroyed")
    }

    private fun connect() {
        val dev = device ?: return
        Log.d(TAG, "Connecting to the device...")
        connection = GattConnection(
            applicationContext, this,
            GattConnection.Config(
                requestMtu = null,
                highPriority = false,
                phyMask = true,
                refreshServiceCache = true,
                discoveryDelayMs = DISCOVERY_DELAY_MS,
                waitForBond = true,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
            ),
        ).also { it.connect(dev) }
    }

    private fun reconnect() {
        Log.d(TAG, "Reconnecting to the device...")
        handler.postDelayed({ connect() }, RECONNECT_DELAY_MS)
    }

    // ---- The script ----------------------------------------------------------

    @Suppress("MissingPermission")
    override fun onConnected(deviceName: String?) {
        val c = connection ?: return
        val dev = device ?: return
        when {
            c.hasService(BleUuids.DFU_CONTROL_SERVICE) -> {
                if (!c.hasCharacteristic(BleUuids.DFU_CONTROL_SERVICE, BleUuids.DFU_CONTROL_CHARACTERISTIC)) {
                    Log.w(TAG, "Cannot find DFU legacy characteristic")
                    fail(R.string.error_missing_characteristic)
                    c.disconnect()
                    return
                }
                generation = BoardGeneration.NRF51
                Log.i(TAG, "Reading DFU control characteristic to initiate pairing")
                c.read(BleUuids.DFU_CONTROL_SERVICE, BleUuids.DFU_CONTROL_CHARACTERISTIC) { value ->
                    // The read itself is what triggers pairing on nRF51; its
                    // result does not matter.
                    Log.d(TAG, "Characteristic read: ${value?.contentToString() ?: "failed"}")
                    c.disconnect()
                }
            }
            c.hasService(BleUuids.SECURE_DFU_SERVICE) -> {
                Log.i(TAG, "Found Secure DFU Service")
                generation = BoardGeneration.NRF52
                if (dev.bondState == BluetoothDevice.BOND_NONE) {
                    Log.w(TAG, "Device is not bonded. Attempting to bond.")
                    dev.createBond()
                } else {
                    Log.i(TAG, "Device is already bonded.")
                }
                c.disconnect()
            }
            else -> {
                Log.e(TAG, "Cannot find Secure DFU service. No reconnection will be attempted.")
                fail(R.string.error_connection_failed)
                c.disconnect()
            }
        }
    }

    override fun onDisconnected(status: Int) {
        connection = null
        when {
            failed -> stopSelf()
            status == 0 -> finish(success = true)
            status == GattConnection.GATT_DISCONNECTED_BY_DEVICE ||
                status == GattConnection.GATT_CONNECTION_TIMEOUT -> {
                if (deviceDisconnects < numbAttempts) {
                    deviceDisconnects++
                    Log.w(TAG, "${GattStatus.fromCode(status).name}. Reconnect $deviceDisconnects of $numbAttempts.")
                    reconnect()
                } else {
                    Log.e(TAG, "${GattStatus.fromCode(status).name}. Giving up after $deviceDisconnects reconnects.")
                    fail(R.string.error_connection_failed)
                }
            }
            attempts < numbAttempts -> {
                attempts++
                Log.w(TAG, "Connection failed (${GattStatus.fromCode(status).name}), attempt: $attempts")
                reconnect()
            }
            else -> {
                Log.e(TAG, "Connection failed (${GattStatus.fromCode(status).name}), attempts: $attempts")
                fail(GattStatus.fromCode(status).messageRes)
            }
        }
    }

    override fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) = Unit

    override fun onError(message: String) {
        Log.e(TAG, message)
        val c = connection
        fail(if (message.contains("discovery")) R.string.error_service_discovery else R.string.error_connection_failed)
        if (c != null && c.isConnected) c.disconnect()
    }

    // ---- Explicit outcome ------------------------------------------------------

    /** Record the failure and notify the user; the service stops once the link is closed. */
    private fun fail(@StringRes message: Int) {
        if (failed) return
        failed = true
        AppStateRepository.updateNotification(Notification.ERROR, message)
        AppStateRepository.setError(getString(message))
        BoardGeneration.saveCurrent(applicationContext, BoardGeneration.UNKNOWN)
        if (connection?.isConnected != true) stopSelf()
    }

    private fun finish(success: Boolean) {
        if (finished) return
        finished = true
        if (success) {
            Log.d(TAG, "Board generation: $generation")
            AppStateRepository.setIdle()
            AppStateRepository.updateNotification(
                Notification.INFO, getString(R.string.info_mini_conected, getString(generation.labelRes)),
            )
            BoardGeneration.saveCurrent(applicationContext, generation)
        }
        stopSelf()
    }
}
