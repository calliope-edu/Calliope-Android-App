package cc.calliope.mini.core.service.partialflashing

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import cc.calliope.mini.R
import cc.calliope.mini.core.service.GattStatus
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.core.state.Progress
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.zip.CRC32

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

        // UUIDs
        private val PARTIAL_FLASHING_SERVICE: UUID = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8")
        private val PARTIAL_FLASH_CHARACTERISTIC: UUID = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8")
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SECURE_DFU_SERVICE: UUID = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")

        // Generic Attribute Service UUIDs (for Service Changed handling)
        private val GENERIC_ATTRIBUTE_SERVICE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
        private val SERVICE_CHANGED_CHARACTERISTIC: UUID = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")

        // Flash memory page sizes
        private const val PAGE_SIZE_NRF51 = 0x400   // 1KB for V1/V2 (nRF51)
        private const val PAGE_SIZE_NRF52 = 0x1000  // 4KB for V3 (nRF52)

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

        // Magic strings for file type detection
        private const val PXT_MAGIC = "708E3B92C615A841C49866C975EE5197"
        private const val UPY_MAGIC1 = "FE307F59"
        private const val UPY_MAGIC2 = "9DD7B1C1"
        private const val UPY_MAGIC_REGEX = ".*FE307F59.{16}9DD7B1C1.*"

        // Timeouts (optimized)
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val SERVICE_DISCOVERY_DELAY_MS = 600L  // Reduced from 1600-2000ms
        private const val REBOOT_WAIT_MS = 3000L  // Time for device to reboot after reset
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val FLASH_TIMEOUT_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3

        // BLE optimization
        private const val PREFERRED_MTU = 247  // Maximum for BLE 4.2+
        private const val MTU_TIMEOUT_MS = 5_000L

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
    private var isPython = false
    private var dalHash: String? = null
    private var fileHash: String? = null
    private var codeStartAddress = 0L
    private var codeEndAddress = 0L
    private var packetState: Byte = PACKET_STATE_WAITING

    // Synchronization
    private val connectionLock = Object()
    private val operationLock = Object()
    private val regionLock = Object()
    private val packetLock = Object()  // Separate lock for packet acknowledgments
    private val disconnectLock = Object()  // For waiting device-initiated disconnect

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
        ApplicationStateHandler.updateState(State.STATE_BUSY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        resultReceiver = getParcelableExtraCompat(intent, EXTRA_RESULT_RECEIVER)
        deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        filePath = intent?.getStringExtra(EXTRA_FILE_PATH)

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
            Log.e(TAG, "Bluetooth permission not granted")
            finishWithResult(false)
            return START_NOT_STICKY
        }

        if (!BluetoothUtils.isValidBluetoothMAC(deviceAddress)) {
            Log.e(TAG, "Invalid device address: $deviceAddress")
            finishWithResult(false)
            return START_NOT_STICKY
        }

        if (filePath.isNullOrEmpty()) {
            Log.e(TAG, "File path is missing")
            finishWithResult(false)
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
        ApplicationStateHandler.updateNotification(Notification.INFO, getString(R.string.flashing_device_connecting))

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
                ApplicationStateHandler.updateProgress(Progress.PROGRESS_COMPLETED)
                ApplicationStateHandler.updateNotification(Notification.INFO, getString(R.string.flashing_completed))
                finishWithResult(true)
            }
            RESULT_ATTEMPT_DFU -> {
                Log.w(TAG, "Partial flashing not available, fallback to DFU")
                ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.partial_flashing_failed))
                finishWithResult(false)
            }
            else -> {
                Log.e(TAG, "Partial flashing failed")
                ApplicationStateHandler.updateNotification(Notification.ERROR, getString(R.string.partial_flashing_failed))
                finishWithResult(false)
            }
        }
    }

    private fun executePartialFlashing(): Int {
        // Step 1: Connect to device
        if (!connectToDevice()) {
            Log.e(TAG, "Failed to connect")
            return RESULT_ATTEMPT_DFU
        }

        // Step 2: Check device mode and prepare for flashing
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

        // Enable Service Changed indications for proper GATT cache handling (Android P+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!ensureServiceChangedEnabled()) {
                Log.w(TAG, "Failed to enable Service Changed indications, continuing anyway")
            }
        }

        return setupPartialFlashingCharacteristic()
    }

    private fun requestMtuNegotiation(): Boolean {
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
    }

    private fun enableServiceChangedIndication(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
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
    }

    private fun setupPartialFlashingCharacteristic(): Boolean {
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
        return writeCharacteristic(byteArrayOf(STATUS_REQUEST))
    }

    private fun sendResetCommand(): Boolean {
        return writeCharacteristic(byteArrayOf(RESET_COMMAND, MODE_PAIRING))
    }

    private fun writeCharacteristic(data: ByteArray, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = partialFlashCharacteristic ?: return false

        characteristicWritten = false

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            if (gatt.writeCharacteristic(characteristic)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }

        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "Failed to write characteristic: $result")
            return false
        }

        // Wait for write confirmation
        synchronized(operationLock) {
            try {
                operationLock.wait(OPERATION_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                return false
            }
        }

        return characteristicWritten
    }

    private fun attemptPartialFlash(): Int {
        Log.d(TAG, "Starting partial flash: $filePath")

        val startTime = SystemClock.elapsedRealtime()

        ApplicationStateHandler.updateState(State.STATE_FLASHING)
        ApplicationStateHandler.updateNotification(Notification.INFO, getString(R.string.flashing_uploading))

        try {
            val hex = HexUtils(filePath!!)
            if (hex.status != HexUtils.STATUS_INIT) {
                Log.e(TAG, "Failed to open hex file")
                return RESULT_ATTEMPT_DFU
            }

            isPython = false
            val dataPos = findMakeCodeData(hex) ?: run {
                findPythonData(hex)?.also { isPython = true }
            }

            if (dataPos == null) {
                Log.e(TAG, "No partial flash data found in file")
                return RESULT_ATTEMPT_DFU
            }

            Log.d(TAG, "Found data at line ${dataPos.line}, offset ${dataPos.part}")

            // Read memory map from device
            codeStartAddress = 0
            codeEndAddress = 0

            if (!readMemoryMap()) {
                Log.e(TAG, "Failed to read memory map")
                return RESULT_ATTEMPT_DFU
            }

            if (codeStartAddress == 0L || codeEndAddress <= codeStartAddress) {
                Log.e(TAG, "Invalid memory map addresses")
                return RESULT_ATTEMPT_DFU
            }

            // Compare DAL hash
            if (fileHash != dalHash) {
                Log.e(TAG, "Hash mismatch: file=$fileHash, device=$dalHash")
                return RESULT_ATTEMPT_DFU
            }

            Log.d(TAG, "Hash match confirmed, starting flash")

            // Flash the data
            val flashResult = flashData(hex, dataPos, startTime)

            val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
            Log.i(TAG, "Flash completed in $elapsed seconds")

            return flashResult

        } catch (e: Exception) {
            Log.e(TAG, "Flash error: ${e.message}", e)
            return RESULT_FAILED
        }
    }

    private fun flashData(hex: HexUtils, dataPos: HexPos, startTime: Long): Int {
        // Count actual data lines (type 0 or 0x0D) from start position
        val numOfLines = countDataLines(hex, dataPos.line)
        Log.d(TAG, "flashData: numOfLines=$numOfLines starting from line ${dataPos.line}")
        var packetNum = 0
        var lineCount = 0
        var part = dataPos.part
        var line0 = 0
        var part0 = 0
        var addr0Lo = 0
        var addr0Hi = 0
        var count = 0
        var endOfFile = false

        while (true) {
            // Timeout check
            if (SystemClock.elapsedRealtime() - startTime > FLASH_TIMEOUT_MS) {
                Log.e(TAG, "Flash timeout")
                return RESULT_FAILED
            }

            // Check EOF
            if (endOfFile || hex.getRecordTypeFromIndex(dataPos.line + lineCount) != 0) {
                if (count == 0) break
                endOfFile = true
            }

            val hexData: String
            val partData: String
            val addr: Long

            if (endOfFile) {
                // Padding mode - complete the batch with FF bytes
                // Don't read address from hex file - use previous value
                hexData = "F".repeat(32)
                partData = hexData
                addr = 0 // Address not used for padding (offset is 0 for count >= 2)
            } else {
                // Read data and address from hex file
                val addrLo = hex.getRecordAddressFromIndex(dataPos.line + lineCount)
                val addrHi = hex.getSegmentAddress(dataPos.line + lineCount)
                addr = addrLo.toLong() + addrHi.toLong() * 256 * 256

                hexData = hex.getDataFromIndex(dataPos.line + lineCount)
                partData = if (part + 32 > hexData.length) {
                    hexData.substring(part)
                } else {
                    hexData.substring(part, part + 32)
                }
            }

            val offsetToSend = when (count) {
                0 -> {
                    // Save start position of this batch
                    line0 = lineCount
                    part0 = part
                    val addr0 = addr + part / 2
                    addr0Lo = (addr0 % (256 * 256)).toInt()
                    addr0Hi = (addr0 / (256 * 256)).toInt()
                    addr0Lo
                }
                1 -> addr0Hi  // Use saved high address from count 0
                else -> 0     // Padding packets use offset 0
            }

            // Build and send packet
            val chunk = HexUtils.recordToByteArray(partData, offsetToSend, packetNum)
            if (!writeCharacteristicNoResponse(chunk)) {
                Log.e(TAG, "Failed to write packet $packetNum")
                return RESULT_FAILED
            }

            count++
            if (count == 4) {
                count = 0

                // Update progress
                val percent = (100 * lineCount / numOfLines.coerceAtLeast(1))
                ApplicationStateHandler.updateProgress(percent)

                // Wait for acknowledgment from device (uses separate lock)
                synchronized(packetLock) {
                    val timeout = SystemClock.elapsedRealtime()
                    while (packetState == PACKET_STATE_WAITING) {
                        try {
                            packetLock.wait(OPERATION_TIMEOUT_MS)
                        } catch (e: InterruptedException) {
                            return RESULT_FAILED
                        }
                        if (SystemClock.elapsedRealtime() - timeout > OPERATION_TIMEOUT_MS) {
                            Log.e(TAG, "Packet acknowledgment timeout")
                            return RESULT_FAILED
                        }
                    }
                }

                // Handle retransmit
                if (packetState == PACKET_STATE_RETRANSMIT) {
                    Log.w(TAG, "Retransmit requested for packets starting at line $line0")
                    lineCount = line0
                    part = part0
                    endOfFile = false
                }

                // Reset state for next batch
                packetState = PACKET_STATE_WAITING
            }

            if (packetState != PACKET_STATE_RETRANSMIT && !endOfFile) {
                part += partData.length
                if (part >= hexData.length) {
                    part = 0
                    lineCount++
                }
            }

            packetNum++
        }

        // Send end of flash
        Thread.sleep(100)
        writeCharacteristicNoResponse(byteArrayOf(END_OF_FLASH_COMMAND))
        Log.d(TAG, "End of flash command sent, waiting for device to process...")

        // Wait longer for V2 (nRF51) to process the end of flash command and start rebooting
        // V2 is slower and needs more time before we disconnect
        Thread.sleep(if (isNrf52) 100 else 500)

        ApplicationStateHandler.updateProgress(100)
        return RESULT_SUCCESS
    }

    private fun writeCharacteristicNoResponse(data: ByteArray): Boolean {
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

        // Wait for write callback even for NO_RESPONSE - Android requires this
        synchronized(operationLock) {
            try {
                operationLock.wait(1000)
            } catch (e: InterruptedException) {
                return false
            }
        }

        return characteristicWritten
    }

    private fun readMemoryMap(): Boolean {
        Log.d(TAG, "Reading memory map")

        try {
            for (i in 0..2) {
                val payload = byteArrayOf(REGION_INFO_COMMAND, i.toByte())
                if (!writeCharacteristic(payload)) {
                    Log.e(TAG, "Failed to request region $i")
                    return false
                }

                synchronized(regionLock) {
                    try {
                        regionLock.wait(2000)
                    } catch (e: InterruptedException) {
                        return false
                    }
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
                Log.e(TAG, "GATT Error $status: ${GattStatus.fromCode(status)?.description ?: "Unknown error"}")
            }
        }
    }

    // GATT Callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val statusDesc = GattStatus.fromCode(status)?.description ?: "Unknown ($status)"
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
                        synchronized(disconnectLock) {
                            disconnectLock.notifyAll()
                        }
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
                // Log all discovered services for debugging
                Log.d(TAG, "=== Discovered ${gatt.services.size} services ===")
                gatt.services.forEach { service ->
                    Log.d(TAG, "Service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        Log.d(TAG, "  - Characteristic: ${char.uuid}")
                    }
                }
                Log.d(TAG, "=== End of services ===")

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
            Log.d(TAG, "onCharacteristicWrite: status=$status")
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

            Log.d(TAG, "Notification received: ${bytesToHex(value)}")

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
                    // Region info response
                    if (value.size >= 18) {
                        val region = value[1].toInt()

                        if (region == REGION_MAKECODE) {
                            codeStartAddress = bytesToLong(value, 2)
                            codeEndAddress = bytesToLong(value, 6)
                        }

                        if (region == REGION_DAL) {
                            dalHash = bytesToHex(value.copyOfRange(10, 18))
                        }
                    }

                    synchronized(regionLock) {
                        regionLock.notifyAll()
                    }
                }
                FLASH_COMMAND -> {
                    // Flash response - use packetLock for acknowledgments
                    if (value.size >= 2) {
                        packetState = value[1]
                        Log.d(TAG, "Flash response: ${String.format("%02X", packetState)}")
                    }
                    synchronized(packetLock) {
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

            // Clear GATT cache before closing - important after device reboot
            BluetoothUtils.clearServicesCache(gatt)
            gatt.disconnect()
            // Wait for disconnect to complete - V2 (nRF51) needs more time
            Thread.sleep(2000)
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
            BluetoothUtils.clearServicesCache(gatt)
            // Don't call disconnect() - device already disconnected or will disconnect
            gatt.close()
        }
        bluetoothGatt = null
        partialFlashCharacteristic = null
    }

    private fun finishWithResult(success: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("result", success)
        }
        resultReceiver?.send(RESULT_OK, bundle)

        ApplicationStateHandler.updateState(if (success) State.STATE_IDLE else State.STATE_BUSY)
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

    // Hex file parsing helpers
    private data class HexPos(
        var line: Int = -1,
        var part: Int = -1,
        var sizeBytes: Int = 0
    )

    private fun findMakeCodeData(hex: HexUtils): HexPos? {
        val line = hex.searchForData(PXT_MAGIC)
        if (line < 0) return null

        val pos = HexPos(line = line)
        val magicData = hex.getDataFromIndex(line)
        pos.part = magicData.indexOf(PXT_MAGIC)

        val hdrAddress = hexPosToAddress(hex, pos)
        val hashAddress = hdrAddress + PXT_MAGIC.length / 2
        val hashPos = hexAddressToPos(hex, hashAddress) ?: return null

        hashPos.sizeBytes = 8
        fileHash = hexGetData(hex, hashPos)

        if ((fileHash?.length ?: 0) < 16) return null

        return pos
    }

    private fun findPythonData(hex: HexUtils): HexPos? {
        val line = hex.searchForDataRegEx(UPY_MAGIC_REGEX)
        if (line < 0) return null

        val pos = HexPos(line = line)
        val header = hex.getDataFromIndex(line)
        pos.part = header.indexOf(UPY_MAGIC1)
        pos.sizeBytes = 16

        val headerData = hexGetData(hex, pos)
        if (headerData.length < 32) return null

        val version = hexToUint16(headerData, 8)
        val tableLen = hexToUint16(headerData, 12)
        val numReg = hexToUint16(headerData, 16)
        val pageLog2 = hexToUint16(headerData, 20)

        if (version != 1) return null
        if (tableLen != numReg * 16) return null

        // Page size depends on hardware: nRF52 (V3) = 4KB, nRF51 (V1/V2) = 1KB
        val pageSize = if (isNrf52) PAGE_SIZE_NRF52 else PAGE_SIZE_NRF51
        if (1 shl pageLog2 != pageSize) return null

        var codeStart = -1L
        var codeLength = -1L

        val hdrAddress = hexPosToAddress(hex, pos)

        for (regionIndex in 0 until numReg) {
            val regionAddress = hdrAddress - tableLen + (regionIndex * 16)
            val regionPos = hexAddressToPos(hex, regionAddress) ?: return null
            regionPos.sizeBytes = 16

            val region = hexGetData(hex, regionPos)
            if (region.length < 32) return null

            val regionID = hexToUint8(region, 0)
            val hashType = hexToUint8(region, 2)
            val startPage = hexToUint16(region, 4)
            val length = hexToUint32(region, 8)
            val hashPtr = hexToUint32(region, 16)
            val hash = region.substring(16, 32)

            val regionHash: String? = when (hashType) {
                0 -> null
                1 -> hash
                2 -> {
                    val hashPos2 = hexAddressToPos(hex, hashPtr) ?: return null
                    hashPos2.sizeBytes = 100
                    val hashData = hexGetData(hex, hashPos2)
                    if (hashData.isEmpty()) return null

                    var strLen = 0
                    while (strLen < hashData.length / 2) {
                        val chr = hexToUint8(hashData, strLen * 2)
                        if (chr == 0) break
                        strLen++
                    }

                    val strBytes = ByteArray(strLen)
                    for (i in 0 until strLen) {
                        strBytes[i] = hexToUint8(hashData, i * 2).toByte()
                    }

                    val crc32 = CRC32()
                    crc32.update(strBytes)
                    val crc = crc32.value
                    val hashBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(crc).array()
                    bytesToHex(hashBytes)
                }
                else -> return null
            }

            when (regionID) {
                2 -> fileHash = regionHash
                3 -> {
                    codeStart = startPage.toLong() * pageSize
                    codeLength = length
                }
            }
        }

        if (codeStart < 0 || codeLength < 0) return null

        val codePos = hexAddressToPos(hex, codeStart) ?: return null
        codePos.sizeBytes = codeLength.toInt()
        return codePos
    }

    private fun hexPosToAddress(hex: HexUtils, pos: HexPos): Long {
        val addrLo = hex.getRecordAddressFromIndex(pos.line)
        val addrHi = hex.getSegmentAddress(pos.line)
        return addrLo.toLong() + addrHi.toLong() * 256 * 256 + pos.part / 2
    }

    private fun hexAddressToPos(hex: HexUtils, address: Long): HexPos? {
        val line = hex.searchForAddress(address)
        if (line < 0) return null

        val lineAddr = hex.getRecordAddressFromIndex(line)
        val addressLo = address % 0x10000
        val offset = (addressLo - lineAddr).toInt() * 2

        return HexPos(line = line, part = offset)
    }

    private fun hexGetData(hex: HexUtils, pos: HexPos): String {
        val data = StringBuilder()
        var line = pos.line
        var part = pos.part
        var size = pos.sizeBytes * 2

        while (size > 0 && line < hex.numOfLines()) {
            val type = hex.getRecordTypeFromIndex(line)
            if (type != 0 && type != 0x0D) {
                line++
                part = 0
                continue
            }

            val lineData = hex.getDataFromIndex(line)
            val chunk = minOf(lineData.length - part, size)

            if (chunk > 0) {
                data.append(lineData.substring(part, part + chunk))
                part += chunk
                size -= chunk
            }

            if (size > 0 && part >= lineData.length) {
                line++
                part = 0
            }
        }

        return data.toString()
    }

    private fun hexToUint8(hex: String, offset: Int): Int {
        return hex.substring(offset, offset + 2).toInt(16)
    }

    private fun hexToUint16(hex: String, offset: Int): Int {
        val lo = hex.substring(offset, offset + 2).toInt(16)
        val hi = hex.substring(offset + 2, offset + 4).toInt(16)
        return lo + hi * 256
    }

    private fun hexToUint32(hex: String, offset: Int): Long {
        val b0 = hex.substring(offset, offset + 2).toLong(16)
        val b1 = hex.substring(offset + 2, offset + 4).toLong(16)
        val b2 = hex.substring(offset + 4, offset + 6).toLong(16)
        val b3 = hex.substring(offset + 6, offset + 8).toLong(16)
        return b0 + b1 * 256 + b2 * 256 * 256 + b3 * 256 * 256 * 256
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

    /**
     * Count the number of data lines (record type 0) starting from the given line.
     * Matches the behavior of flashData loop which stops at any non-type-0 record.
     */
    private fun countDataLines(hex: HexUtils, startLine: Int): Int {
        var count = 0
        var line = startLine
        val totalLines = hex.numOfLines()

        while (line < totalLines) {
            val recordType = hex.getRecordTypeFromIndex(line)
            // flashData only accepts type 0, stops on anything else
            if (recordType == 0) {
                count++
                line++
            } else {
                // Any other record type (including extended address, EOF) - stop
                break
            }
        }

        Log.d(TAG, "countDataLines: counted $count data lines from line $startLine")
        return count.coerceAtLeast(1) // Avoid division by zero
    }
}
