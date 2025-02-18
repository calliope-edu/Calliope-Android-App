package cc.calliope.mini.core.service

import android.app.Activity.RESULT_OK
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import cc.calliope.mini.core.service.LegacyDfuService.Companion.EXTRA_NUMB_ATTEMPTS
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Notification.ERROR
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.BluetoothUtils
import cc.calliope.mini.utils.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class PartialFlashingInitService : Service() {
    companion object {
        const val TAG = "PFBaseService"
        const val GATT_DISCONNECTED_BY_DEVICE = 19
        const val DEFAULT_NUMB_ATTEMPTS = 3
        const val STATUS: Byte = 0xEE.toByte()
        const val MODE_APPLICATION: Byte = 0x01.toByte()
        const val STATUS_RESPONSE_TIMEOUT_MS = 10_000L
    }

    private var attempts = 0
    private var numbAttempts = DEFAULT_NUMB_ATTEMPTS
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var errorCounter = 0

    private val PARTIAL_FLASH_CHARACTERISTIC: UUID = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8")
    private val PARTIAL_FLASHING_SERVICE: UUID = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8")
    private val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var partialFlashingCharacteristic: BluetoothGattCharacteristic? = null

    private var resultReceiver: ResultReceiver? = null

    @SuppressWarnings("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val statusDescription = GattStatus.fromCode(status)?.description ?: "Unknown Status ($status)"
            val newStateDescription = ConnectionState.fromCode(newState)?.description ?: "Unknown State ($newState)"
            Log.d(TAG, "onConnectionStateChange: status=$status ($statusDescription), newState=$newState ($newStateDescription)")

            when (status) {
                GATT_SUCCESS -> {
                    handleGattSuccess(gatt, newState)
                }
                GATT_DISCONNECTED_BY_DEVICE -> {
                    handleDisconnectByDevice(gatt)
                }
                GATT_INSUFFICIENT_AUTHORIZATION -> {
                    handleInsufficientAuthorization(gatt)
                }
                else -> {
                    handleGattFailure(gatt, status)
                }
            }
        }

        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicRead(gatt, characteristic, characteristic.value, status)")
        )
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val statusDescription = GattStatus.fromCode(status)?.description ?: "Unknown Status ($status)"
            Log.d(TAG, "onCharacteristicRead: status=$status ($statusDescription)")
        }

        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicChanged(gatt, characteristic, characteristic.value)")
        )
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "onCharacteristicChanged")

            if (value[0] == STATUS){
                Log.i(TAG, "Status: ${if (value[2] == MODE_APPLICATION) "Application Mode" else "Pairing Mode"}")
                if (value[2] == MODE_APPLICATION) {
                    //Reset (0x00 for Pairing Mode, 0x01 for Application Mode)
                    writeCharacteristic(gatt, 0xFF.toByte(), 0x00.toByte())
                } else {
                    //all ok, finish
                    gatt.disconnect()
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val statusDescription = GattStatus.fromCode(status)?.description ?: "Unknown Status ($status)"
            Log.d(TAG, "onCharacteristicWrite: status=$status ($statusDescription)")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val statusDescription = GattStatus.fromCode(status)?.description ?: "Unknown Status ($status)"
            Log.d(TAG, "onDescriptorWrite: status=$status ($statusDescription)")

            if (status == GATT_SUCCESS) {
                sendStatusRequest(gatt)
            } else {
                handleError(gatt, "Failed to write descriptor for characteristic: ${descriptor.characteristic.uuid}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val statusDescription = GattStatus.fromCode(status)?.description ?: "Unknown Status ($status)"
            Log.d(TAG, "onServicesDiscovered: status=$status ($statusDescription)")

            if (status == GATT_SUCCESS) {
                handleServicesDiscovered(gatt)
            } else {
                handleGattFailure(gatt, status)
            }
        }

        private fun sendStatusRequest(gatt: BluetoothGatt) {
            Log.d(TAG, "Send status request...")
            if (!writeCharacteristic(gatt, 0xEE.toByte())) {
                handleError(gatt, "Failed to send status request")
            } else {
                serviceScope.launch {
                    delay(STATUS_RESPONSE_TIMEOUT_MS)
                    Log.e(TAG, "No response received within ${STATUS_RESPONSE_TIMEOUT_MS / 1000} seconds")
                    errorCounter++
                    stopService(gatt)
                }
            }
        }

        @Suppress("DEPRECATION")
        @SuppressWarnings("MissingPermission")
        private fun writeCharacteristic(gatt: BluetoothGatt, vararg data: Byte): Boolean {
            Log.d(TAG, "Writing characteristic: ${data.contentToString()}")
            partialFlashingCharacteristic?.let { characteristic ->
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.value =data
                    gatt.writeCharacteristic(characteristic)
                }
            }
            Log.e(TAG, "Partial flashing characteristic is null")
            return false
        }

        private fun handleGattSuccess(gatt: BluetoothGatt, newState: Int) {
            when (newState) {
                STATE_CONNECTED -> handleConnectedState(gatt)
                STATE_DISCONNECTED -> stopService(gatt)
                else -> Log.w(TAG, "Unknown state: $newState")
            }
        }

        private fun handleDisconnectByDevice(gatt: BluetoothGatt) {
            Log.w(TAG, "Disconnected by device. Will wait for 2 seconds before attempting to reconnect.")
            reConnect(gatt.device.address)
        }

        private fun handleInsufficientAuthorization(gatt: BluetoothGatt) {
            Log.w(TAG, "Insufficient authorization. Will wait for 2 seconds before attempting to reconnect.")
            reConnect(gatt.device.address)
        }

        private fun handleConnectedState(gatt: BluetoothGatt) {
            val bondState = gatt.device.bondState
            if (bondState == BluetoothDevice.BOND_BONDING) {
                Log.w(TAG, "Waiting for bonding to complete")
            } else {
                Log.d(TAG, "Wait for 2000 millis before service discovery")
                serviceScope.launch {
                    delay(2000)
                    startServiceDiscovery(gatt)
                }
            }
        }

        private fun startServiceDiscovery(gatt: BluetoothGatt) {
            Log.d(TAG, "Starting service discovery on device: ${gatt.device.address}")
            var result = false

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                serviceScope.launch {
                    Log.d(TAG, "Wait for 1600 milliseconds before starting service discovery")
                    delay(1600)
                    result = gatt.discoverServices()
                }
            } else {
                result = gatt.discoverServices()
            }
            handleServiceDiscoveryResult(result, gatt)
        }

        @SuppressWarnings("MissingPermission")
        private fun handleServiceDiscoveryResult(result: Boolean, gatt: BluetoothGatt) {
            if (result) {
                Log.i(TAG, "Service discovery initiated successfully")
            } else {
                handleError(gatt, "Failed to start service discovery")
            }
        }

        private fun handleServicesDiscovered(gatt: BluetoothGatt) {
            Log.i(TAG, "Services discovered successfully")
            try {
                getPartialFlashingService(gatt)
            } catch (e: Exception) {
                handleError(gatt, "Error while handling services: ${e.message}")
            }
        }

        private fun handleGattFailure(gatt: BluetoothGatt, status: Int) {
            val statusDescription = GattStatus.fromCode(status)?.description ?: "Unknown Status ($status)"
            if (attempts < numbAttempts) {
                Log.w(TAG, "Connection failed, attempts: $attempts; Error: $status $statusDescription")
                reConnect(gatt.device.address)
            } else {
                handleError(gatt, "Connection failed, attempts: $attempts; Error: $status $statusDescription")
            }
        }

        @SuppressWarnings("MissingPermission")
        private fun getPartialFlashingService(gatt: BluetoothGatt) {
            serviceScope.launch {
                try {
                    val partialFlashingService = gatt.getService(PARTIAL_FLASHING_SERVICE)
                    if (partialFlashingService == null) {
                        handleError(gatt, "Failed to get partial flashing service: $PARTIAL_FLASHING_SERVICE")
                        return@launch
                    }

                    partialFlashingCharacteristic = partialFlashingService.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC)
                    if (partialFlashingCharacteristic == null) {
                        handleError(gatt, "Failed to get partial flashing characteristic: $PARTIAL_FLASH_CHARACTERISTIC")
                        return@launch
                    }

                    if (!gatt.setCharacteristicNotification(partialFlashingCharacteristic, true)) {
                        handleError(gatt, "Failed to enable notifications for characteristic: $PARTIAL_FLASH_CHARACTERISTIC")
                        return@launch
                    }

                    val descriptor = partialFlashingCharacteristic!!.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
                            handleError(gatt, "Failed to write descriptor for characteristic: $PARTIAL_FLASH_CHARACTERISTIC")
                            return@launch
                        }
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (!gatt.writeDescriptor(descriptor)) {
                            handleError(gatt, "Failed to write descriptor for characteristic: $PARTIAL_FLASH_CHARACTERISTIC")
                            return@launch
                        }
                    }

                    Log.i(TAG, "Waiting for descriptor write to complete...")
                } catch (e: Exception) {
                    handleError(gatt, "Error while getting partial flashing service: ${e.message}")
                }
            }
        }

        private fun handleError(gatt: BluetoothGatt, message: String) {
            Log.e(TAG, message)
            errorCounter++
            gatt.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtra(intent: Intent?, name: String): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(name, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(name)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        ApplicationStateHandler.updateState(State.STATE_BUSY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Get the pending intent from the intent
        resultReceiver = getParcelableExtra(intent,"resultReceiver")

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
            Log.e(LegacyDfuService.TAG, "BLUETOOTH permission no granted")
            stopSelf()
            return START_NOT_STICKY
        }

        val address = intent?.getStringExtra(LegacyDfuService.EXTRA_DEVICE_ADDRESS)
        numbAttempts = intent?.getIntExtra(
            EXTRA_NUMB_ATTEMPTS,
            LegacyDfuService.DEFAULT_NUMB_ATTEMPTS
        ) ?: LegacyDfuService.DEFAULT_NUMB_ATTEMPTS
        connect(address)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()

        val bundle = Bundle()
        bundle.putBoolean("result", errorCounter == 0)
        resultReceiver?.send(RESULT_OK, bundle)

        //ApplicationStateHandler.updateState(State.STATE_IDLE)
        Log.d(TAG, "Service destroyed")
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(address: String?, autoConnect: Boolean = false) {
        Log.d(TAG, "Connecting to the device with autoConnect: $autoConnect")

        serviceScope.launch {
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter? = bluetoothManager.adapter

            if (adapter == null || !adapter.isEnabled || !BluetoothUtils.isValidBluetoothMAC(address)) {
                ApplicationStateHandler.updateNotification(ERROR, "Bluetooth adapter is null or not enabled")
                ApplicationStateHandler.updateState(State.STATE_IDLE)
                stopSelf()
                return@launch
            }

            try {
                val device = adapter.getRemoteDevice(address)
                if (device == null) {
                    Log.e(TAG, "Device is null")
                    errorCounter++
                    stopSelf()
                    return@launch
                }

                connectToDevice(device, autoConnect)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get remote device: ${e.message}")
                errorCounter++
                stopSelf()
            }
        }
    }

    private fun reConnect(address: String?) {
        Log.d(TAG, "Reconnecting to the device...")
        serviceScope.launch {
            delay(2000)
            connect(address)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, autoConnect: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    this,
                    autoConnect,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    this,
                    autoConnect,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(
                    this,
                    autoConnect,
                    gattCallback
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device: ${e.message}")
            errorCounter++
            stopSelf()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        clearServicesCache(gatt)
        gatt.close()
        stopSelf()
    }

    private fun clearServicesCache(gatt: BluetoothGatt) {
        Log.i(TAG, "Refreshing device cache...")
        try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val success = refresh.invoke(gatt) as Boolean
            Log.d(TAG, "Refreshing result: $success")
        } catch (e: Exception) {
            Log.e(TAG, "An exception occurred while refreshing device. $e"
            )
        }
    }
}