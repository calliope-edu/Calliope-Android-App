package cc.calliope.mini.core.service

import android.app.Activity.RESULT_OK
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import cc.calliope.mini.R
import cc.calliope.mini.core.bluetooth.BleUuids
import cc.calliope.mini.core.bluetooth.GattConnection
import cc.calliope.mini.core.bluetooth.GattStatus
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.FlashPhase
import cc.calliope.mini.core.state.Notification.ERROR
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import java.util.UUID

/**
 * Legacy DFU trigger for nRF51 boards (mini 1/2): reboot the board into
 * its DFU bootloader so Nordic DFU can take over.
 *
 * The script (hardware contract 2, verified on real boards — keep the
 * order and the delays):
 *  1. connect with a 2 s settle before discovery, cache refreshed;
 *  2. READ the DFU control characteristic first, then WRITE 0x01 to it;
 *  3. remove the bond (Nordic DFU ≥ 2.7 would otherwise wait for a
 *     Service Changed indication the V2 bootloader never sends);
 *  4. disconnect, wait 2 s, close, and report the result to the caller
 *     through the [ResultReceiver] — FlashingService then waits a further
 *     3 s before starting Nordic DFU.
 *
 * A link the board drops (status 19) or a failed connect is retried
 * [numbAttempts] times; the result is `false` if the write never happened.
 */
class LegacyDfuService : Service(), GattConnection.Listener {

    companion object {
        const val TAG = "LegacyDfuService"
        const val EXTRA_DEVICE_ADDRESS = Constants.CURRENT_DEVICE_ADDRESS
        const val EXTRA_RESULT_RECEIVER = "resultReceiver"
        const val EXTRA_NUMB_ATTEMPTS = Constants.EXTRA_NUMB_ATTEMPTS
        const val DEFAULT_NUMB_ATTEMPTS = 1

        /** Settle time between connect and service discovery (contract 2). */
        const val DISCOVERY_DELAY_MS = 2000L
        /** Between the link going down and `close()` (contract 2). */
        const val CLOSE_DELAY_MS = 2000L
        /** Before a reconnect attempt after a drop or a failed connect. */
        const val RECONNECT_DELAY_MS = 2000L
        /** Nothing should take longer than this before discovery completes. */
        const val CONNECT_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var connection: GattConnection? = null
    private var device: BluetoothDevice? = null
    private var resultReceiver: ResultReceiver? = null
    private var numbAttempts = DEFAULT_NUMB_ATTEMPTS
    private var attempts = 0
    /** True once the 0x01 write completed: the board is rebooting into DFU. */
    private var isComplete = false
    /** We closed the link ourselves to try again (service not found yet). */
    private var retryPending = false

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Legacy DFU Service started")
        resultReceiver = getReceiver(intent)

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
            Log.e(TAG, "BLUETOOTH permission not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        numbAttempts = intent?.getIntExtra(EXTRA_NUMB_ATTEMPTS, DEFAULT_NUMB_ATTEMPTS) ?: DEFAULT_NUMB_ATTEMPTS
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        val adapter = BluetoothUtils.getAdapter(this)
        if (adapter == null || !adapter.isEnabled || !BluetoothUtils.isValidBluetoothMAC(address)) {
            AppStateRepository.updateNotification(ERROR, getString(R.string.error_bluetooth_adapter_null))
            stopSelf()
            return START_NOT_STICKY
        }
        device = try { adapter.getRemoteDevice(address) } catch (_: Exception) { null }
        if (device == null) {
            Log.e(TAG, "Device is null")
            AppStateRepository.updateNotification(ERROR, getString(R.string.error_device_null))
            stopSelf()
            return START_NOT_STICKY
        }

        connect()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        connection?.disconnect()
        connection = null
        resultReceiver?.send(RESULT_OK, Bundle().apply { putBoolean("result", isComplete) })
        Log.d(TAG, "Legacy DFU Service destroyed (result=$isComplete)")
    }

    private fun connect() {
        val dev = device ?: return
        Log.d(TAG, "Connecting to the device...")
        AppStateRepository.flashPhase(FlashPhase.CONNECTING)
        connection = GattConnection(
            applicationContext, this,
            GattConnection.Config(
                requestMtu = null,
                highPriority = false,
                phyMask = true,
                refreshServiceCache = true,
                discoveryDelayMs = DISCOVERY_DELAY_MS,
                waitForBond = true,
                closeDelayMs = CLOSE_DELAY_MS,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
            ),
        ).also { it.connect(dev) }
    }

    private fun reconnect() {
        Log.d(TAG, "Reconnecting to the device...")
        handler.postDelayed({ connect() }, RECONNECT_DELAY_MS)
    }

    // ---- GattConnection.Listener ---------------------------------------------

    override fun onConnected(deviceName: String?) {
        val c = connection ?: return
        val service: UUID = BleUuids.DFU_CONTROL_SERVICE
        val characteristic: UUID = BleUuids.DFU_CONTROL_CHARACTERISTIC
        if (!c.hasService(service)) {
            if (attempts < numbAttempts) {
                Log.w(TAG, "Cannot find DFU legacy service. Attempt: $attempts")
                attempts++
                retryPending = true
                c.disconnect()
                return
            }
            Log.e(TAG, "Cannot find DFU legacy service. Attempts: $attempts")
            AppStateRepository.updateNotification(ERROR, getString(R.string.error_missing_dfu_service))
            c.disconnect()
            return
        }
        if (!c.hasCharacteristic(service, characteristic)) {
            Log.e(TAG, "Cannot find DFU legacy characteristic")
            AppStateRepository.updateNotification(ERROR, getString(R.string.error_missing_dfu_characteristic))
            c.disconnect()
            return
        }
        // READ first, WRITE in the read callback — the order the bootloader
        // trigger was verified with.
        c.read(service, characteristic) { value ->
            if (value == null) {
                Log.w(TAG, "Characteristic read failed")
                c.disconnect()
                return@read
            }
            Log.d(TAG, "Characteristic read: ${value.contentToString()} — writing flash command")
            c.write(service, characteristic, byteArrayOf(1), withResponse = true) { ok ->
                if (ok) {
                    Log.d(TAG, "Flash command written successfully")
                    isComplete = true
                    // The board now reboots into its DFU bootloader.
                    AppStateRepository.flashPhase(FlashPhase.REBOOTING)
                    removeBondIfNeeded()
                } else {
                    Log.e(TAG, "Error writing characteristic")
                }
                c.disconnect()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun removeBondIfNeeded() {
        val dev = device ?: return
        if (dev.bondState == BluetoothDevice.BOND_BONDED) {
            Log.d(TAG, "Removing bond before DFU...")
            BluetoothUtils.removeBond(dev)
        }
    }

    override fun onDisconnected(status: Int) {
        connection = null
        when {
            isComplete -> stopSelf()
            retryPending -> {
                retryPending = false
                reconnect()
            }
            status == 0 -> stopSelf()
            attempts < numbAttempts -> {
                attempts++
                Log.w(TAG, "Link down (${GattStatus.fromCode(status).name}). Attempt: $attempts")
                reconnect()
            }
            else -> {
                Log.e(TAG, "Link down (${GattStatus.fromCode(status).name}). Attempts: $attempts")
                stopSelf()
            }
        }
    }

    override fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) = Unit

    override fun onError(message: String) {
        Log.w(TAG, message)
        val c = connection
        if (c != null && c.isConnected) c.disconnect() else stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun getReceiver(intent: Intent?): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }
}
