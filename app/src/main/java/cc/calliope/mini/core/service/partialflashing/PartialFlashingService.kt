package cc.calliope.mini.core.service.partialflashing

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import cc.calliope.mini.R
import cc.calliope.mini.ui.activity.NotificationActivity
import cc.calliope.mini.core.bluetooth.GattStatus
import cc.calliope.mini.core.hex.FlashLayout
import cc.calliope.mini.core.hex.IntelHexFile
import cc.calliope.mini.core.hex.PartialFlashImage
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.core.state.FlashPhase
import cc.calliope.mini.core.state.FlashResult
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import kotlinx.coroutines.*
import java.util.*

/**
 * Unified Partial Flashing Service
 * Combines initialization and flashing in a single connection for faster operation.
 *
 * Based on original work:
 * (c) 2017 - 2021, Micro:bit Educational Foundation and contributors
 * SPDX-License-Identifier: MIT
 */
@SuppressLint("MissingPermission")
class PartialFlashingService : Service() {

    companion object {
        private const val TAG = "PartialFlashingService"

        // Intent extras
        const val EXTRA_DEVICE_ADDRESS = "deviceAddress"
        const val EXTRA_FILE_PATH = "filepath"
        const val EXTRA_RESULT_RECEIVER = "resultReceiver"
        /** Key of the result code in the ResultReceiver bundle. */
        const val KEY_RESULT = "result"

        // UUIDs
        private val PARTIAL_FLASHING_SERVICE: UUID = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8")
        private val PARTIAL_FLASH_CHARACTERISTIC: UUID = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8")
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SECURE_DFU_SERVICE: UUID = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")

        // Generic Attribute Service UUIDs (for Service Changed handling)
        private val GENERIC_ATTRIBUTE_SERVICE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
        private val SERVICE_CHANGED_CHARACTERISTIC: UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")

        // Status commands
        private const val STATUS_REQUEST: Byte = 0xEE.toByte()
        private const val MODE_APPLICATION: Byte = 0x01
        private const val MODE_PAIRING: Byte = 0x00
        private const val RESET_COMMAND: Byte = 0xFF.toByte()

        // Partial flashing commands
        private const val REGION_INFO_COMMAND: Byte = 0x00
        private const val FLASH_COMMAND: Byte = 0x01
        private const val END_OF_FLASH_COMMAND: Byte = 0x02

        // Packet states
        private const val PACKET_STATE_WAITING: Byte = 0x00
        private const val PACKET_STATE_OK: Byte = 0xFF.toByte()
        private const val PACKET_STATE_RETRANSMIT: Byte = 0xAA.toByte()

        // Regions
        private const val REGION_SD = 0
        private const val REGION_DAL = 1
        private const val REGION_MAKECODE = 2

        // Results
        const val RESULT_SUCCESS = 0
        const val RESULT_ATTEMPT_DFU = 1
        const val RESULT_FAILED = 2

        // Timeouts (optimized)
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 1600L  // Nordic recommendation for Service Changed indication
        private const val REBOOT_WAIT_MS = 3000L  // Time for device to reboot after reset
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val FLASH_TIMEOUT_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3

        // BLE optimization
        private const val PREFERRED_MTU = 247  // Maximum for BLE 4.2+
        private const val MTU_TIMEOUT_MS = 5_000L

        // Foreground notification
        private const val NOTIFICATION_CHANNEL_ID = "partial_flashing_channel"
        private const val NOTIFICATION_ID = 200

        // GATT error codes that warrant retry
        private const val GATT_ERROR = 133  // Generic timeout/disconnect
        private const val GATT_BUSY = 22    // Device busy
        private const val GATT_AUTH_FAIL = 5  // Authentication failure
        private const val GATT_DISCONNECTED_BY_DEVICE = 19  // Device initiated disconnect (reboot)
    }

    // Service state
    private var resultReceiver: ResultReceiver? = null
    private var deviceAddress: String? = null
    private var filePath: String? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // BLE state
    private var bluetoothGatt: BluetoothGatt? = null
    private var partialFlashCharacteristic: BluetoothGattCharacteristic? = null

    // Flashing state
    private var isNrf52 = false  // true = nRF52 (V3), false = nRF51 (V1/V2)
    // Written on the BLE binder thread (handleCharacteristicChanged) and read
    // from the Dispatchers.IO worker — @Volatile plus writes under regionLock /
    // packetLock keep them coherent across the two threads.
    @Volatile private var dalHash: String? = null
    private var fileHash: String? = null
    /** DAL region bounds reported by the device. A zero range
     *  (start==0 && end==0) signals the firmware was built without
     *  `addlayouttable.py` — true for the blocks-runtime CODAL build —
     *  and means partial flash cannot work on this device regardless of
     *  what the hex carries. Must fall back to full DFU. */
    @Volatile private var dalStartAddress = 0L
    @Volatile private var dalEndAddress = 0L
    @Volatile private var codeStartAddress = 0L
    @Volatile private var codeEndAddress = 0L
    private var packetState: Byte = PACKET_STATE_WAITING  // Guarded by packetLock
    @Volatile private var regionReceived = BooleanArray(3)  // Track which REGION_INFO responses arrived

    // Synchronization
    private val connectionLock = Object()
    private val operationLock = Object()
    private val regionLock = Object()
    private val packetLock = Object()  // Separate lock for packet acknowledgments
    private val disconnectLock = Object()  // For waiting device-initiated disconnect
    private val gattLock = java.util.concurrent.locks.ReentrantLock()  // Serialize GATT operations

    // Callbacks tracking
    @Volatile private var isConnected = false
    @Volatile private var servicesDiscovered = false
    @Volatile private var mtuNegotiated = false
    @Volatile private var descriptorWritten = false
    @Volatile private var descriptorReadValue: ByteArray? = null
    @Volatile private var characteristicWritten = false
    @Volatile private var currentMode: Byte = MODE_APPLICATION
    @Volatile private var statusResponseReceived = false  // Track if status response notification arrived
    @Volatile private var waitingForReboot = false
    @Volatile private var disconnectedByDevice = false  // Track if device initiated disconnect (reboot)
    @Volatile private var negotiatedMtu = 23  // Default BLE MTU

    // Handler for delayed operations (avoid blocking Bluetooth thread)
    private val bleHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        startForegroundWithNotification()
    }

    private fun startForegroundWithNotification() {
        // Create notification channel (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.partial_flashing_starting),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.partial_flashing_starting)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // Same icon and tint as the Nordic DFU notification, so a flash
        // looks the same in the status bar whichever path it takes.
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.partial_flashing_starting))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setColor(Color.GRAY)
            .setContentIntent(NotificationActivity.contentIntent(this))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        resultReceiver = getParcelableExtraCompat(intent, EXTRA_RESULT_RECEIVER)
        deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        filePath = intent?.getStringExtra(EXTRA_FILE_PATH)

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
            Log.e(TAG, "Bluetooth permission not granted")
            finishWithResult(RESULT_FAILED)
            return START_NOT_STICKY
        }

        if (!BluetoothUtils.isValidBluetoothMAC(deviceAddress)) {
            Log.e(TAG, "Invalid device address: $deviceAddress")
            finishWithResult(RESULT_FAILED)
            return START_NOT_STICKY
        }

        if (filePath.isNullOrEmpty()) {
            Log.e(TAG, "File path is missing")
            finishWithResult(RESULT_FAILED)
            return START_NOT_STICKY
        }

        serviceScope.launch {
            startPartialFlashing()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        bleHandler.removeCallbacksAndMessages(null)  // Clean up pending callbacks
        disconnectAndClose()
        Log.d(TAG, "Service destroyed")
    }

    private suspend fun startPartialFlashing() {
        AppStateRepository.flashPhase(FlashPhase.CONNECTING)
        AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_device_connecting))

        val result = withContext(Dispatchers.IO) {
            try {
                executePartialFlashing()
            } catch (e: Exception) {
                Log.e(TAG, "Partial flashing failed: ${e.message}", e)
                RESULT_FAILED
            }
        }

        when (result) {
            RESULT_SUCCESS -> {
                Log.i(TAG, "Partial flashing completed successfully")
                AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_completed))
                AppStateRepository.finishFlash(FlashResult.Success)
                finishWithResult(RESULT_SUCCESS)
            }
            RESULT_ATTEMPT_DFU -> {
                Log.w(TAG, "Partial flashing not available, fallback to DFU")
                AppStateRepository.updateNotification(Notification.WARNING, getString(R.string.partial_flashing_failed))
                finishWithResult(RESULT_ATTEMPT_DFU)
            }
            else -> {
                Log.e(TAG, "Partial flashing failed")
                AppStateRepository.updateNotification(Notification.ERROR, getString(R.string.partial_flashing_failed))
                finishWithResult(RESULT_FAILED)
            }
        }
    }

    private fun executePartialFlashing(): Int {
        // Step 1: Connect to device with retry
        var connected = false
        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            if (connectToDevice()) {
                connected = true
                break
            }
            Log.w(TAG, "Connection attempt $attempt of $MAX_RECONNECT_ATTEMPTS failed")
            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                val delay = attempt * 1000L
                Log.d(TAG, "Retrying in ${delay}ms...")
                Thread.sleep(delay)
            }
        }
        if (!connected) {
            Log.e(TAG, "Failed to connect after $MAX_RECONNECT_ATTEMPTS attempts")
            return RESULT_ATTEMPT_DFU
        }

        // Step 2: Check device mode and prepare for flashing
        AppStateRepository.flashPhase(FlashPhase.PREPARING)
        AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_process_starting))
        if (!prepareDeviceForFlashing()) {
            Log.e(TAG, "Failed to prepare device")
            return RESULT_ATTEMPT_DFU
        }

        // Step 3: Execute flashing
        return attemptPartialFlash()
    }

    private fun connectToDevice(): Boolean {
        Log.d(TAG, "Connecting to device: $deviceAddress")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter not available")
            return false
        }

        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get remote device: ${e.message}")
            return false
        }

        isConnected = false
        servicesDiscovered = false

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(
                this, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }

        if (bluetoothGatt == null) {
            Log.e(TAG, "Failed to create GATT connection")
            return false
        }

        // Wait for connection
        synchronized(connectionLock) {
            val startTime = SystemClock.elapsedRealtime()
            while (!isConnected && SystemClock.elapsedRealtime() - startTime < CONNECTION_TIMEOUT_MS) {
                try {
                    connectionLock.wait(1000)
                } catch (e: InterruptedException) {
                    return false
                }
            }
        }

        if (!isConnected) {
            Log.e(TAG, "Connection timeout")
            return false
        }

        // Request high connection priority for faster transfers
        bluetoothGatt?.let { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                Log.d(TAG, "Requested high connection priority")
            }
        }

        // Request larger MTU for faster data transfer
        if (!requestMtuNegotiation()) {
            Log.w(TAG, "MTU negotiation failed, using default MTU")
            // Continue anyway with default MTU
        }

        // Wait for service discovery
        synchronized(connectionLock) {
            val startTime = SystemClock.elapsedRealtime()
            while (!servicesDiscovered && SystemClock.elapsedRealtime() - startTime < CONNECTION_TIMEOUT_MS) {
                try {
                    connectionLock.wait(1000)
                } catch (e: InterruptedException) {
                    return false
                }
            }
        }

        if (!servicesDiscovered) {
            Log.e(TAG, "Service discovery timeout")
            return false
        }

        // No bonding here, on purpose. The partial-flashing characteristic
        // needs no encryption (micro:bit implementations never bond for it),
        // and a createBond() at this point made an nRF51 board reboot right
        // after BOND_BONDED to apply encryption — killing the session mid
        // memory-map and leaving the fallback DFU to start from a dirty state.

        // Enable Service Changed indications for proper GATT cache handling (Android P+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!ensureServiceChangedEnabled()) {
                Log.w(TAG, "Failed to enable Service Changed indications, continuing anyway")
            }
        }

        return setupPartialFlashingCharacteristic()
    }

    private fun requestMtuNegotiation(): Boolean {
        gattLock.lock()
        try {
            val gatt = bluetoothGatt ?: return false

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Log.d(TAG, "MTU negotiation not supported on this API level")
                return true  // Not an error, just not supported
            }

            mtuNegotiated = false

            if (!gatt.requestMtu(PREFERRED_MTU)) {
                Log.e(TAG, "Failed to request MTU")
                return false
            }

            // Wait for MTU callback
            synchronized(connectionLock) {
                val startTime = SystemClock.elapsedRealtime()
                while (!mtuNegotiated && SystemClock.elapsedRealtime() - startTime < MTU_TIMEOUT_MS) {
                    try {
                        connectionLock.wait(500)
                    } catch (e: InterruptedException) {
                        return false
                    }
                }
            }

            Log.d(TAG, "MTU negotiation completed: $negotiatedMtu bytes")
            return mtuNegotiated
        } finally {
            gattLock.unlock()
        }
    }

    /**
     * Ensures Service Changed indications are enabled for proper GATT cache handling.
     * This is important for bonded devices to receive cache invalidation notifications.
     */
    private fun ensureServiceChangedEnabled(): Boolean {
        val gatt = bluetoothGatt ?: return false

        val serviceChangedChar = getServiceChangedCharacteristic(gatt)
        if (serviceChangedChar == null) {
            Log.d(TAG, "Service Changed characteristic not found (normal for some devices)")
            return true  // Not an error, some devices don't have it
        }

        // Check if already enabled
        if (isServiceChangedIndicationEnabled(gatt, serviceChangedChar)) {
            Log.d(TAG, "Service Changed indication already enabled")
            return true
        }

        // Enable indication
        Log.d(TAG, "Enabling Service Changed indication")
        return enableServiceChangedIndication(gatt, serviceChangedChar)
    }

    private fun getServiceChangedCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val gasService = gatt.getService(GENERIC_ATTRIBUTE_SERVICE) ?: return null
        return gasService.getCharacteristic(SERVICE_CHANGED_CHARACTERISTIC)
    }

    private fun isServiceChangedIndicationEnabled(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        gattLock.lock()
        try {
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return false

            descriptorReadValue = null

            // Read current descriptor value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.readDescriptor(descriptor)
            } else {
                @Suppress("DEPRECATION")
                gatt.readDescriptor(descriptor)
            }

            // Wait for read callback
            synchronized(operationLock) {
                val startTime = SystemClock.elapsedRealtime()
                while (descriptorReadValue == null && SystemClock.elapsedRealtime() - startTime < OPERATION_TIMEOUT_MS) {
                    try {
                        operationLock.wait(500)
                    } catch (e: InterruptedException) {
                        return false
                    }
                }
            }

            val value = descriptorReadValue ?: return false
            if (value.size != 2) return false

            // Check if indication is enabled (0x02, 0x00)
            return value[0] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[0] &&
                   value[1] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[1]
        } finally {
            gattLock.unlock()
        }
    }

    private fun enableServiceChangedIndication(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        gattLock.lock()
        try {
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG) ?: return false

            // Enable local notification
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                Log.e(TAG, "Failed to enable Service Changed notification locally")
                return false
            }

            descriptorWritten = false

            // Write indication enable to descriptor
            val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (writeResult != BluetoothStatusCodes.SUCCESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.e(TAG, "Failed to write Service Changed descriptor")
                return false
            }

            // Wait for write callback
            synchronized(operationLock) {
                val startTime = SystemClock.elapsedRealtime()
                while (!descriptorWritten && SystemClock.elapsedRealtime() - startTime < OPERATION_TIMEOUT_MS) {
                    try {
                        operationLock.wait(500)
                    } catch (e: InterruptedException) {
                        return false
                    }
                }
            }

            Log.d(TAG, "Service Changed indication enabled: $descriptorWritten")
            return descriptorWritten
        } finally {
            gattLock.unlock()
        }
    }

    private fun setupPartialFlashingCharacteristic(): Boolean {
        gattLock.lock()
        try {
            val gatt = bluetoothGatt ?: return false

            val pfService = gatt.getService(PARTIAL_FLASHING_SERVICE)
            if (pfService == null) {
                Log.e(TAG, "Partial flashing service not found")
                return false
            }

            partialFlashCharacteristic = pfService.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC)
            if (partialFlashCharacteristic == null) {
                Log.e(TAG, "Partial flashing characteristic not found")
                return false
            }

            // Enable notifications
            if (!gatt.setCharacteristicNotification(partialFlashCharacteristic, true)) {
                Log.e(TAG, "Failed to enable notifications")
                return false
            }

            val descriptor = partialFlashCharacteristic!!.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                Log.e(TAG, "CCC descriptor not found")
                return false
            }

            descriptorWritten = false

            val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            if (writeResult != BluetoothStatusCodes.SUCCESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.e(TAG, "Failed to write descriptor")
                return false
            }

            // Wait for descriptor write
            synchronized(operationLock) {
                val startTime = SystemClock.elapsedRealtime()
                while (!descriptorWritten && SystemClock.elapsedRealtime() - startTime < OPERATION_TIMEOUT_MS) {
                try {
                    operationLock.wait(500)
                } catch (e: InterruptedException) {
                    return false
                }
            }
        }

            return descriptorWritten
        } finally {
            gattLock.unlock()
        }
    }

    private fun prepareDeviceForFlashing(): Boolean {
        Log.d(TAG, "Preparing device for flashing (hardware: ${if (isNrf52) "nRF52/V3" else "nRF51/V2"})")

        // Reset status tracking
        statusResponseReceived = false

        // Send status request (don't fail if write callback is slow - we'll check notification)
        sendStatusRequest()

        // Wait for status response notification
        synchronized(operationLock) {
            val startTime = SystemClock.elapsedRealtime()
            while (!statusResponseReceived && SystemClock.elapsedRealtime() - startTime < OPERATION_TIMEOUT_MS) {
                try {
                    operationLock.wait(500)
                } catch (e: InterruptedException) {
                    return false
                }
            }
        }

        if (!statusResponseReceived) {
            Log.e(TAG, "No status response received from device")
            return false
        }

        Log.d(TAG, "Device mode: ${if (currentMode == MODE_APPLICATION) "Application" else "Pairing"}")

        if (currentMode == MODE_APPLICATION) {
            // Need to reboot device into pairing mode
            Log.d(TAG, "Sending reset command to enter pairing mode")
            AppStateRepository.flashPhase(FlashPhase.REBOOTING)

            // Reset disconnect tracking
            disconnectedByDevice = false
            waitingForReboot = true

            if (!sendResetCommand()) {
                Log.e(TAG, "Failed to send reset command")
                waitingForReboot = false
                return false
            }

            // Wait for the device to disconnect itself (it will reboot after receiving reset command)
            // Don't actively disconnect - the device needs time to process the command
            Log.d(TAG, "Waiting for device to reboot and disconnect...")

            synchronized(disconnectLock) {
                val startTime = SystemClock.elapsedRealtime()
                while (!disconnectedByDevice && SystemClock.elapsedRealtime() - startTime < OPERATION_TIMEOUT_MS) {
                    try {
                        disconnectLock.wait(500)
                    } catch (e: InterruptedException) {
                        waitingForReboot = false
                        return false
                    }
                }
            }

            if (!disconnectedByDevice) {
                Log.w(TAG, "Device did not disconnect after reset command, forcing disconnect")
            }

            // Clean up GATT connection (device may have already disconnected)
            closeGatt()

            Log.d(TAG, "Waiting ${REBOOT_WAIT_MS}ms for device to complete reboot...")
            Thread.sleep(REBOOT_WAIT_MS)

            // Try to reconnect with multiple attempts
            var reconnected = false
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                Log.d(TAG, "Reconnect attempt $attempt of $MAX_RECONNECT_ATTEMPTS")

                if (connectToDevice()) {
                    reconnected = true
                    break
                }

                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "Reconnect failed, waiting before retry...")
                    Thread.sleep(1000)
                }
            }

            waitingForReboot = false

            if (!reconnected) {
                Log.e(TAG, "Failed to reconnect after reboot")
                return false
            }

            // After reboot, device should be in pairing mode - no need to check again
            Log.d(TAG, "Reconnected successfully after reboot")
        }

        return true
    }

    private fun sendStatusRequest(): Boolean {
        return writeCharacteristicNoResponse(byteArrayOf(STATUS_REQUEST))
    }

    private fun sendResetCommand(): Boolean {
        return writeCharacteristicNoResponse(byteArrayOf(RESET_COMMAND, MODE_PAIRING))
    }

    private fun attemptPartialFlash(): Int {
        Log.d(TAG, "Starting partial flash: $filePath")

        val startTime = SystemClock.elapsedRealtime()

        // Reading the memory map and comparing hashes is still preparation;
        // UPLOADING (and its "Uploading…" notice) starts in flashData, once
        // bytes actually go over the air — otherwise "validating" was the
        // last thing the user saw before "completed".
        AppStateRepository.flashPhase(FlashPhase.PREPARING)
        AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_firmware_validating))

        try {
            val hex = try {
                IntelHexFile.read(filePath!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open hex file: $e")
                return RESULT_ATTEMPT_DFU
            }
            Log.d(TAG, "Hex file loaded: ${hex.size} lines")

            val layout = if (isNrf52) FlashLayout.NRF52 else FlashLayout.NRF51
            val image = PartialFlashImage.locate(hex, layout)
            if (image == null) {
                Log.e(TAG, "No partial flash data found in file")
                return RESULT_ATTEMPT_DFU
            }
            fileHash = image.runtimeHash
            Log.d(TAG, "Found ${if (image.isMicroPython) "MicroPython" else "MakeCode"} data at line ${image.startLine}, offset ${image.startNibble}")

            // Read memory map from device
            codeStartAddress = 0
            codeEndAddress = 0
            dalStartAddress = 0
            dalEndAddress = 0

            if (!readMemoryMap()) {
                Log.e(TAG, "Failed to read memory map")
                return RESULT_ATTEMPT_DFU
            }

            // DAL zero-range sentinel — matches the widget's check at
            // ble-flash-web.ts:597-599. The blocks-runtime CODAL build
            // doesn't ship `addlayouttable.py`, so its partial-flash
            // service responds to REGION_INFO with all-zero start/end
            // and an all-zero hash. The legacy hash compare alone could
            // be fooled into "match confirmed" if the file's hash also
            // happened to be all zeros (rare but possible). The widget
            // catches this defensively before any hash compare; do the
            // same here so a Blocks → MakeCode swap reliably refuses
            // partial flash and falls back to full DFU.
            if (dalStartAddress == 0L && dalEndAddress == 0L) {
                Log.w(TAG, "DAL region reports zero range — device has no partial-flash layout table (likely blocks-runtime), falling back to full DFU")
                AppStateRepository.updateNotification(
                    Notification.INFO,
                    getString(R.string.partial_flashing_declined_no_layout_table)
                )
                return RESULT_ATTEMPT_DFU
            }

            if (codeStartAddress == 0L || codeEndAddress <= codeStartAddress) {
                Log.w(TAG, "MakeCode region zero/invalid — partial-flash layout malformed, falling back to full DFU")
                AppStateRepository.updateNotification(
                    Notification.INFO,
                    getString(R.string.partial_flashing_declined_invalid_memory_map)
                )
                return RESULT_ATTEMPT_DFU
            }

            // Compare DAL hash. A hex without one cannot be verified, so it
            // never partial-flashes. Open Roberta hexes carry no hash yet;
            // once Roberta adds it on their side, fileHash becomes non-null
            // and partial flashing re-enables automatically via the normal
            // comparison below.
            if (fileHash == null) {
                Log.w(TAG, "No DAL hash (partial hex / Open Roberta) — falling back to DFU")
                return RESULT_ATTEMPT_DFU
            }
            if (dalHash == null) {
                Log.w(TAG, "Device DAL hash missing — cannot verify runtime compatibility, falling back to full DFU")
                AppStateRepository.updateNotification(
                    Notification.INFO,
                    getString(R.string.partial_flashing_declined_device_hash_unavailable)
                )
                return RESULT_ATTEMPT_DFU
            }
            if (fileHash != dalHash) {
                Log.e(TAG, "Hash mismatch: file=$fileHash, device=$dalHash — falling back to full DFU")
                AppStateRepository.updateNotification(
                    Notification.INFO,
                    getString(R.string.partial_flashing_declined_runtime_mismatch, fileHash, dalHash)
                )
                return RESULT_ATTEMPT_DFU
            }
            Log.d(TAG, "Hash match confirmed: $fileHash")

            // Verify code start address matches hex file
            if (image.codeStartAddress != codeStartAddress) {
                Log.e(TAG, "Code start address mismatch: file=0x${String.format("%08X", image.codeStartAddress)}, device=0x${String.format("%08X", codeStartAddress)}")
                return RESULT_ATTEMPT_DFU
            }
            Log.d(TAG, "Code start address verified: 0x${String.format("%08X", codeStartAddress)}, starting flash")

            // Flash the data
            AppStateRepository.flashPhase(FlashPhase.UPLOADING)
            AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_uploading))
            val flashResult = flashData(image, startTime)

            val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
            Log.i(TAG, "Flash completed in $elapsed seconds")

            return flashResult

        } catch (e: Exception) {
            Log.e(TAG, "Flash error: ${e.message}", e)
            return RESULT_FAILED
        }
    }

    private fun flashData(image: PartialFlashImage, startTime: Long): Int {
        val batches = image.batches()
        Log.d(TAG, "flashData: ${batches.size} batches, ${image.dataLineCount} data lines from line ${image.startLine}")
        // Packet numbers only ever count up — a retransmitted batch goes out
        // under new numbers, which is what the board expects.
        var packetNum = 0
        var index = 0

        while (index < batches.size) {
            if (SystemClock.elapsedRealtime() - startTime > FLASH_TIMEOUT_MS) {
                Log.e(TAG, "Flash timeout")
                return RESULT_FAILED
            }

            val batch = batches[index]
            for (packet in batch.packets) {
                if (!writeCharacteristicNoResponse(packet.encode(packetNum))) {
                    Log.e(TAG, "Failed to write packet $packetNum")
                    return RESULT_FAILED
                }
                packetNum++
            }

            AppStateRepository.flashProgress(100 * batch.linesSent / image.dataLineCount)

            // Wait for acknowledgment from device (uses separate lock)
            var ack: Byte
            synchronized(packetLock) {
                val timeout = SystemClock.elapsedRealtime()
                while (packetState == PACKET_STATE_WAITING) {
                    try {
                        packetLock.wait(OPERATION_TIMEOUT_MS)
                    } catch (e: InterruptedException) {
                        return RESULT_FAILED
                    }
                    if (SystemClock.elapsedRealtime() - timeout > OPERATION_TIMEOUT_MS) {
                        Log.w(TAG, "Packet acknowledgment timeout at batch $index, treating as retransmit")
                        packetState = PACKET_STATE_RETRANSMIT
                        break
                    }
                }
                ack = packetState
                packetState = PACKET_STATE_WAITING
            }

            // Retransmit (explicit from device or timeout): same batch again.
            if (ack == PACKET_STATE_RETRANSMIT) {
                Log.w(TAG, "Retransmit batch $index (from line ${image.startLine + batch.linesSent})")
                continue
            }
            index++
        }

        // Send end of flash
        Thread.sleep(100)
        writeCharacteristicNoResponse(byteArrayOf(END_OF_FLASH_COMMAND))
        Log.d(TAG, "End of flash command sent, waiting for device to process...")

        // Wait longer for V2 (nRF51) to process the end of flash command and start rebooting
        // V2 is slower and needs more time before we disconnect
        Thread.sleep(if (isNrf52) 100 else 500)

        AppStateRepository.flashProgress(100)
        return RESULT_SUCCESS
    }

    private fun writeCharacteristicNoResponse(data: ByteArray): Boolean {
        gattLock.lock()
        try {
            val gatt = bluetoothGatt ?: return false
            val characteristic = partialFlashCharacteristic ?: return false

            characteristicWritten = false

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic, data,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }

            if (!result) {
                Log.e(TAG, "Failed to write characteristic (no response)")
                return false
            }
            // Wait for write callback even for NO_RESPONSE - Android requires this.
            // Guarded wait: if the callback fires before we get here, the flag is
            // already set and we don't sleep out the full timeout on a lost notify.
            synchronized(operationLock) {
                val deadline = SystemClock.elapsedRealtime() + 1000
                while (!characteristicWritten) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    try {
                        operationLock.wait(remaining)
                    } catch (e: InterruptedException) {
                        return false
                    }
                }
            }

            return characteristicWritten
        } finally {
            gattLock.unlock()
        }
    }

    private fun readMemoryMap(): Boolean {
        Log.d(TAG, "Reading memory map")

        regionReceived = BooleanArray(3)

        try {
            for (i in 0..2) {
                val payload = byteArrayOf(REGION_INFO_COMMAND, i.toByte())
                if (!writeCharacteristicNoResponse(payload)) {
                    Log.e(TAG, "Failed to request region $i")
                    return false
                }

                synchronized(regionLock) {
                    val deadline = SystemClock.elapsedRealtime() + 2000
                    while (!regionReceived.getOrElse(i) { false }) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        try {
                            regionLock.wait(remaining)
                        } catch (e: InterruptedException) {
                            return false
                        }
                    }
                }

                if (!regionReceived.getOrElse(i) { false }) {
                    Log.e(TAG, "No response received for region $i")
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read memory map: ${e.message}")
            return false
        }
    }

    /**
     * Handle specific GATT errors with appropriate logging and potential recovery hints
     */
    private fun handleGattError(status: Int) {
        when (status) {
            GATT_ERROR -> {
                // Error 133 - most common, usually timeout or device went out of range
                Log.e(TAG, "GATT Error 133: Connection timeout or device disconnected unexpectedly. " +
                        "This often occurs when device is out of range or busy.")
            }
            GATT_BUSY -> {
                // Error 22 - device is busy with another operation
                Log.e(TAG, "GATT Error 22: Device is busy. " +
                        "Another BLE operation may be in progress.")
            }
            GATT_AUTH_FAIL -> {
                // Error 5 - authentication/pairing failed
                Log.e(TAG, "GATT Error 5: Authentication failed. " +
                        "Device may need to be re-paired.")
            }
            else -> {
                Log.e(TAG, "GATT Error $status: ${GattStatus.fromCode(status).name}")
            }
        }
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val statusDesc = "${GattStatus.fromCode(status).name} ($status)"
            Log.d(TAG, "onConnectionStateChange: status=$statusDesc, newState=$newState")

            // Handle device-initiated disconnect (reboot)
            if (status == GATT_DISCONNECTED_BY_DEVICE) {
                Log.d(TAG, "Device initiated disconnect (reboot)")
                isConnected = false
                if (waitingForReboot) {
                    disconnectedByDevice = true
                    synchronized(disconnectLock) {
                        disconnectLock.notifyAll()
                    }
                }
                synchronized(connectionLock) {
                    connectionLock.notifyAll()
                }
                return
            }

            // Handle GATT errors
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattError(status)
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true

                    // Use Handler for delayed service discovery (don't block Bluetooth thread)
                    // After reboot we need more time for device to stabilize
                    val delay = if (waitingForReboot) 1600L else SERVICE_DISCOVERY_DELAY_MS
                    Log.d(TAG, "Scheduling service discovery after ${delay}ms")

                    bleHandler.postDelayed({
                        if (isConnected && bluetoothGatt != null) {
                            BluetoothUtils.clearServicesCache(gatt)
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Failed to start service discovery")
                            }
                        }
                    }, delay)

                    synchronized(connectionLock) {
                        connectionLock.notifyAll()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnected = false
                    // Remove any pending callbacks
                    bleHandler.removeCallbacksAndMessages(null)

                    // Signal if we were waiting for device to reboot
                    if (waitingForReboot) {
                        Log.d(TAG, "Device disconnected during reboot wait")
                        disconnectedByDevice = true
                    }

                    synchronized(disconnectLock) {
                        disconnectLock.notifyAll()
                    }
                    synchronized(connectionLock) {
                        connectionLock.notifyAll()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Detect hardware type by presence of Secure DFU service
                isNrf52 = gatt.getService(SECURE_DFU_SERVICE) != null
                Log.d(TAG, "Hardware: ${if (isNrf52) "nRF52 (V3)" else "nRF51 (V1/V2)"}, page size: ${if (isNrf52) "4KB" else "1KB"}")

                // Check for Partial Flashing service
                val pfService = gatt.getService(PARTIAL_FLASHING_SERVICE)
                if (pfService != null) {
                    Log.d(TAG, "Partial Flashing service found!")
                } else {
                    Log.w(TAG, "Partial Flashing service NOT found. Expected UUID: $PARTIAL_FLASHING_SERVICE")
                }

                servicesDiscovered = true
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
            }

            synchronized(connectionLock) {
                connectionLock.notifyAll()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.i(TAG, "MTU successfully changed to $mtu bytes (payload: ${mtu - 3} bytes)")
            } else {
                Log.w(TAG, "MTU change failed, using default MTU")
                negotiatedMtu = 23  // Default
            }

            mtuNegotiated = true
            synchronized(connectionLock) {
                connectionLock.notifyAll()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: status=$status")
            descriptorWritten = status == BluetoothGatt.GATT_SUCCESS

            synchronized(operationLock) {
                operationLock.notifyAll()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorRead: status=$status")
            @Suppress("DEPRECATION")
            descriptorReadValue = if (status == BluetoothGatt.GATT_SUCCESS) descriptor.value else null

            synchronized(operationLock) {
                operationLock.notifyAll()
            }
        }

        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            Log.d(TAG, "onDescriptorRead: status=$status, value=${bytesToHex(value)}")
            descriptorReadValue = if (status == BluetoothGatt.GATT_SUCCESS) value else null

            synchronized(operationLock) {
                operationLock.notifyAll()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "onCharacteristicWrite: status=$status")
            characteristicWritten = status == BluetoothGatt.GATT_SUCCESS

            synchronized(operationLock) {
                operationLock.notifyAll()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleCharacteristicChanged(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicChanged(value)
        }

        private fun handleCharacteristicChanged(value: ByteArray) {
            if (value.isEmpty()) return


            when (value[0]) {
                STATUS_REQUEST -> {
                    // Status response
                    if (value.size >= 3) {
                        currentMode = value[2]
                        statusResponseReceived = true
                        Log.d(TAG, "Status response: mode=${if (currentMode == MODE_APPLICATION) "Application" else "Pairing"}")
                    }
                    synchronized(operationLock) {
                        operationLock.notifyAll()
                    }
                }
                REGION_INFO_COMMAND -> {
                    // Region info response: [0x00, region, startAddr(4B), endAddr(4B), hash(8B)]
                    if (value.size >= 18) {
                        val region = value[1].toInt()
                        val regionName = when (region) {
                            REGION_SD -> "SoftDevice"
                            REGION_DAL -> "DAL"
                            REGION_MAKECODE -> "MakeCode"
                            else -> "Unknown($region)"
                        }

                        val startAddr = bytesToLong(value, 2)
                        val endAddr = bytesToLong(value, 6)
                        val hash = bytesToHex(value.copyOfRange(10, 18))

                        Log.d(TAG, "Region $regionName: " +
                                "start=0x${String.format("%08X", startAddr)}, " +
                                "end=0x${String.format("%08X", endAddr)}, " +
                                "hash=$hash, " +
                                "raw=[${value.joinToString(" ") { String.format("%02X", it) }}]")

                        // Publish the region data under the same lock the reader
                        // waits on, so the IO worker never sees a torn update.
                        synchronized(regionLock) {
                            if (region in 0..2) {
                                regionReceived[region] = true
                            }

                            if (region == REGION_MAKECODE) {
                                codeStartAddress = startAddr
                                codeEndAddress = endAddr
                            }

                            if (region == REGION_DAL) {
                                dalStartAddress = startAddr
                                dalEndAddress = endAddr
                                dalHash = hash
                            }
                        }
                    }

                    synchronized(regionLock) {
                        regionLock.notifyAll()
                    }
                }
                FLASH_COMMAND -> {
                    // Flash response - use packetLock for acknowledgments
                    synchronized(packetLock) {
                        if (value.size >= 2) {
                            packetState = value[1]
                            if (packetState != 0xFF.toByte()) Log.d(TAG, "Flash response: ${String.format("%02X", packetState)}")
                        }
                        packetLock.notifyAll()
                    }
                }
            }
        }
    }

    private fun disconnectAndClose() {
        bluetoothGatt?.let { gatt ->
            // Reset connection priority to balanced before disconnecting (saves battery)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reset connection priority: ${e.message}")
                }
            }

            gatt.disconnect()

            // Wait for onConnectionStateChange(STATE_DISCONNECTED) callback
            synchronized(disconnectLock) {
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (isConnected) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    try {
                        disconnectLock.wait(remaining)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            gatt.close()
        }
        bluetoothGatt = null
        partialFlashCharacteristic = null
    }

    /**
     * Close GATT without actively disconnecting.
     * Used when device has already disconnected itself (e.g., after reboot command).
     */
    private fun closeGatt() {
        bluetoothGatt?.let { gatt ->
            // Don't call disconnect() - device already disconnected or will disconnect
            gatt.close()
        }
        bluetoothGatt = null
        partialFlashCharacteristic = null
    }

    /**
     * Report one of [RESULT_SUCCESS], [RESULT_ATTEMPT_DFU] (declined, nothing
     * written) or [RESULT_FAILED] (a real failure) — the caller decides what
     * each means; a boolean used to blur the last two.
     */
    private fun finishWithResult(result: Int) {
        val bundle = Bundle().apply {
            putInt(KEY_RESULT, result)
        }
        resultReceiver?.send(RESULT_OK, bundle)
        // Session state is owned by FlashingService / finishFlash: a failure
        // here is not terminal (FlashingService falls back to full DFU).
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtraCompat(intent: Intent?, name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(name, ResultReceiver::class.java) as? T
        } else {
            intent?.getParcelableExtra(name)
        }
    }

    private fun bytesToLong(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset + 3].toLong() and 0xFF) +
               (bytes[offset + 2].toLong() and 0xFF) * 256 +
               (bytes[offset + 1].toLong() and 0xFF) * 256 * 256 +
               (bytes[offset].toLong() and 0xFF) * 256 * 256 * 256
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
    }
}
