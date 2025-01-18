package cc.calliope.mini.core.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import cc.calliope.mini.R
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.BluetoothUtils
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Constants.MINI_V2
import cc.calliope.mini.utils.Constants.MINI_V3
import cc.calliope.mini.utils.Constants.UNIDENTIFIED
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class BondingService : Service() {
    companion object {
        const val TAG = "BondingService"
        const val GATT_DISCONNECTED_BY_DEVICE = 19
        const val EXTRA_DEVICE_ADDRESS = Constants.CURRENT_DEVICE_ADDRESS
        const val EXTRA_DEVICE_VERSION = Constants.CURRENT_DEVICE_VERSION
        const val EXTRA_NUMB_ATTEMPTS = Constants.EXTRA_NUMB_ATTEMPTS
        const val DEFAULT_NUMB_ATTEMPTS = 2

        private val DFU_CONTROL_SERVICE_UUID = Constants.DFU_CONTROL_SERVICE_UUID
        private val DFU_CONTROL_CHARACTERISTIC_UUID = Constants.DFU_CONTROL_CHARACTERISTIC_UUID
        private val SECURE_DFU_SERVICE_UUID = Constants.SECURE_DFU_SERVICE_UUID

    }

    private var attempts = 0
    private var numbAttempts = DEFAULT_NUMB_ATTEMPTS
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var deviceVersion: Int = UNIDENTIFIED
    private var errorCounter = 0;

    @SuppressWarnings("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: newState=$newState, status=$status")

            when (status) {
                GATT_SUCCESS -> {
                    handleGattSuccess(gatt, newState)
                }
                GATT_DISCONNECTED_BY_DEVICE -> {
                    handleDeviceDisconnection(gatt)
                }
                GATT_INSUFFICIENT_AUTHORIZATION -> {
                    handleInsufficientAuthorization(gatt)
                }
                else -> {
                    handleGattError(gatt, status)
                }
            }
        }

        private fun handleGattSuccess(gatt: BluetoothGatt, newState: Int) {
            when (newState) {
                STATE_CONNECTED -> handleConnectedState(gatt)
                STATE_DISCONNECTED -> stopService(gatt)
                else -> Log.w(TAG, "Unknown state: $newState")
            }
        }

        private fun handleDeviceDisconnection(gatt: BluetoothGatt) {
            Log.w(TAG, "Disconnected by device. Will wait for 2 seconds before attempting to reconnect.")
            reConnect(gatt.device.address)
        }

        private fun handleInsufficientAuthorization(gatt: BluetoothGatt) {
            Log.w(TAG, "Insufficient authorization")
            reConnect(gatt.device.address)
        }

        private fun handleGattError(gatt: BluetoothGatt, status: Int) {
            if (attempts < numbAttempts) {
                Log.w(TAG, "Connection failed, attempt: $attempts")
                attempts++
                reConnect(gatt.device.address)
            } else {
                val message: String = getString(GattStatusUser.get(status).message)
                Log.e(TAG, "Connection failed, attempts: $attempts; Error: $status $message")
                errorCounter++
                notifyError(R.string.error_connection_failed)
                stopService(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")

            if (status == GATT_SUCCESS) {
                handleServicesDiscovered(gatt)
            } else {
                handleServiceDiscoveryFailure(gatt, status)
            }
        }

        private fun handleServicesDiscovered(gatt: BluetoothGatt) {
            Log.i(TAG, "Services discovered successfully")
            try {
                getDfuControlService(gatt)
            } catch (e: Exception) {
                Log.e(TAG, "Error while handling services: ${e.message}")
                errorCounter++
                notifyError(R.string.error_service_discovery)
                gatt.disconnect()
            }
        }

        private fun handleServiceDiscoveryFailure(gatt: BluetoothGatt, status: Int) {
            Log.w(TAG, "Service discovery failed with status: $status")
            errorCounter++
            notifyError(R.string.error_service_discovery)
            gatt.disconnect()
        }

        @Suppress("DEPRECATION")
        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicRead(gatt, characteristic, characteristic.value, status)")
        )
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == GATT_SUCCESS) {
                Log.d(TAG, "Characteristic read: ${characteristic.uuid} Value: ${value.let { it.contentToString() }}")
            } else {
                Log.w(TAG, "Characteristic read failed: ${characteristic.uuid}")
            }
            gatt.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Bonding Service started")

        if (!arePermissionsGranted()) {
            errorCounter++
            Log.e(TAG, "Bluetooth permissions not granted")
            notifyError(R.string.error_bluetooth_permissions)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            Log.e(TAG, "Intent is null, stopping service")
            notifyError(R.string.error_connection_failed)
            stopSelf()
            return START_NOT_STICKY
        }

        val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (address == null) {
            Log.e(TAG, "Device address is null, stopping service")
            notifyError(R.string.error_connection_failed)
            stopSelf()
            return START_NOT_STICKY
        }

        numbAttempts = intent.getIntExtra(EXTRA_NUMB_ATTEMPTS, DEFAULT_NUMB_ATTEMPTS)
        deviceVersion = intent.getIntExtra(EXTRA_DEVICE_VERSION, UNIDENTIFIED)

        try {
            connect(address)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device: ${e.message}")
            notifyError(R.string.error_connection_failed)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun arePermissionsGranted(): Boolean {
        return Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Bonding Service destroyed")
        if(errorCounter == 0) {
            Log.d(TAG, "Device version: $deviceVersion")
            ApplicationStateHandler.updateState(State.STATE_IDLE)
            val versionString = when (deviceVersion) {
                MINI_V2 -> getString(R.string.mini_version_1)  // Use R.string.mini_version_1 for version 1
                MINI_V3 -> getString(R.string.mini_version_2)  // Use R.string.mini_version_2 for version 2
                else -> deviceVersion.toString()  // Default case if version is unknown
            }

            // Get the full message string from resources
            val message = getString(R.string.info_mini_conected, versionString)
            ApplicationStateHandler.updateNotification(Notification.INFO, message)

            Preference.putInt(applicationContext, Constants.CURRENT_DEVICE_VERSION, deviceVersion)
        } else {
            Log.d(TAG, "Device version: $UNIDENTIFIED")
            Preference.putInt(applicationContext, Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED)
        }
        serviceJob.cancel()
    }

    private fun reConnect(address: String?) {
        Log.d(TAG, "Reconnecting to the device...")
        serviceScope.launch {
            delay(2000)
            connect(address)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(address: String?, autoConnect: Boolean = false) {
        Log.d(TAG, "Connecting to the device with autoConnect: $autoConnect")

        serviceScope.launch {
            delay(2000) // Wait for 2 seconds before connecting

            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter? = bluetoothManager.adapter

            if (!isBluetoothEnabled(adapter)) {
                return@launch
            }

            if (!isValidMacAddress(address)) {
                return@launch
            }

            try {
                val device = adapter?.getRemoteDevice(address)
                if (device == null) {
                    Log.e(TAG, "Device is null")
                    errorCounter++
                    notifyError(R.string.error_device_null)
                    stopSelf()
                    return@launch
                }

                connectToDevice(device, autoConnect)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get remote device: ${e.message}")
                errorCounter++
                notifyError(R.string.error_connection_failed)
                stopSelf()
            }
        }
    }

    private fun isBluetoothEnabled(adapter: BluetoothAdapter?): Boolean {
        if (adapter == null || !adapter.isEnabled) {
            errorCounter++
            Log.e(TAG, "Bluetooth is not enabled")
            notifyError(R.string.error_bluetooth_not_enabled)
            stopSelf()
            return false
        }
        return true
    }

    private fun isValidMacAddress(address: String?): Boolean {
        if (!BluetoothUtils.isValidBluetoothMAC(address)) {
            errorCounter++
            Log.e(TAG, "Invalid MAC address")
            notifyError(R.string.error_invalid_mac_address)
            stopSelf()
            return false
        }
        return true
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
            notifyError(R.string.error_connection_failed)
            stopSelf()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun handleConnectedState(gatt: BluetoothGatt) {
        val bondState = gatt.device.bondState
        if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.w(TAG, "Waiting for bonding to complete")
        } else {
            BluetoothUtils.clearServicesCache(gatt)
            Log.d(TAG, "Wait for 2000 millis before service discovery")
            serviceScope.launch {
                delay(2000)
                startServiceDiscovery(gatt)
            }
        }
    }

    @SuppressWarnings("MissingPermission")
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
            Log.e(TAG, "Failed to start service discovery")
            errorCounter++
            notifyError(R.string.error_service_discovery)
            gatt.disconnect()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun getDfuControlService(gatt: BluetoothGatt) {
        try {
            val dfuControlService = gatt.getService(DFU_CONTROL_SERVICE_UUID)
            if (dfuControlService == null) {
                handleMissingDfuService(gatt)
                return
            }

            val dfuControlCharacteristic = dfuControlService.getCharacteristic(
                DFU_CONTROL_CHARACTERISTIC_UUID
            )
            if (dfuControlCharacteristic == null) {
                Log.w(TAG, "Cannot find DFU legacy characteristic: $DFU_CONTROL_CHARACTERISTIC_UUID")
                gatt.disconnect()
                notifyError(R.string.error_missing_characteristic)
                return
            }

            deviceVersion = MINI_V2

            if (!gatt.readCharacteristic(dfuControlCharacteristic)) {
                Log.e(TAG, "Failed to read DFU control characteristic")
                gatt.disconnect()
                notifyError(R.string.error_reading_characteristic)
            } else {
                Log.i(TAG, "Reading DFU control characteristic to initiate pairing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getDfuControlService: ${e.message}")
            gatt.disconnect()
            notifyError(R.string.error_service_discovery)
        }
    }

    private fun handleMissingDfuService(gatt: BluetoothGatt) {
        Log.w(TAG, "Cannot find DFU legacy service: $DFU_CONTROL_SERVICE_UUID")
        getSecureDfuService(gatt)
    }

    @SuppressWarnings("MissingPermission")
    private fun getSecureDfuService(gatt: BluetoothGatt) {
        try {
            val secureDfuService = gatt.getService(SECURE_DFU_SERVICE_UUID)
            if (secureDfuService == null) {
                handleMissingSecureDfuService(gatt)
                return
            }

            Log.i(TAG, "Found Secure DFU Service: $SECURE_DFU_SERVICE_UUID")
            deviceVersion = MINI_V3

            handleBonding(gatt.device)

            gatt.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getSecureDfuService: ${e.message}")
            gatt.disconnect()
            notifyError(R.string.error_service_discovery)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun handleBonding(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.w(TAG, "Device is not bonded. Attempting to bond.")
            device.createBond()
        } else {
            Log.i(TAG, "Device is already bonded.")
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun handleMissingSecureDfuService(gatt: BluetoothGatt) {
        Log.e(TAG, "Cannot find Secure DFU service. No reconnection will be attempted.")
        errorCounter++
        notifyError(R.string.error_connection_failed)
        gatt.disconnect()
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        BluetoothUtils.clearServicesCache(gatt)
        gatt.close()
        stopSelf()
    }

    private fun notifyError(message: Int) {
        ApplicationStateHandler.updateNotification(Notification.ERROR, message)
        ApplicationStateHandler.updateState(State.STATE_ERROR)
    }
}
