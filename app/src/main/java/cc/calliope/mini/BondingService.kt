package cc.calliope.mini

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
import android.util.Log.ASSERT
import cc.calliope.mini.notification.Notification.TYPE_ERROR
import cc.calliope.mini.notification.Notification.TYPE_INFO
import cc.calliope.mini.notification.NotificationManager
import cc.calliope.mini.service.GattStatus
import cc.calliope.mini.state.State.STATE_ERROR
import cc.calliope.mini.state.State.STATE_READY
import cc.calliope.mini.state.StateManager
import cc.calliope.mini.utils.BluetoothUtils
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Constants.MINI_V1
import cc.calliope.mini.utils.Constants.MINI_V2
import cc.calliope.mini.utils.Constants.UNIDENTIFIED
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.Preference
import cc.calliope.mini.utils.Utils
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
            Utils.log(Log.DEBUG, TAG, "onConnectionStateChange: $newState")
            when (status) {
                GATT_SUCCESS -> {
                    when (newState) {
                        STATE_CONNECTED -> handleConnectedState(gatt)
                        STATE_DISCONNECTED -> stopService(gatt)
                    }
                }
                GATT_DISCONNECTED_BY_DEVICE -> {
                    Utils.log(Log.WARN, TAG, "Disconnected by device")
                    stopService(gatt)
                    //reConnect(gatt.device.address)
                }
                GATT_INSUFFICIENT_AUTHORIZATION -> {
                    Utils.log(Log.WARN, TAG, "Insufficient authorization")
                }
                else -> {
                    if (attempts < numbAttempts) {
                        Utils.log(Log.WARN, TAG, "Connection failed, attempt: $attempts")
                        attempts++
                        reConnect(gatt.device.address)
                    } else {
                        val message: String = getString(GattStatus.get(status).message)
                        Utils.log(Log.ERROR, TAG, "Connection failed, attempts: $attempts; Error: $status $message")
                        errorCounter++
                        notifyError()
                        stopService(gatt)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Utils.log(Log.DEBUG, TAG, "onServicesDiscovered: $status")

            if (status == GATT_SUCCESS) {
                getDfuControlService(gatt)
            } else {
                gatt.disconnect()
                Utils.log(Log.WARN, TAG, "Services discovered not success")
            }
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
                Utils.log(Log.DEBUG, TAG, "Characteristic read: ${characteristic.uuid} Value: ${value.let { it.contentToString() }}")
            } else {
                Utils.log(Log.WARN, TAG, "Characteristic read failed: ${characteristic.uuid}")
            }
            gatt.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Utils.log(Log.DEBUG, TAG, "Service created")
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Utils.log(ASSERT, TAG, "Bonding Service started")

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
            Utils.log(Log.ERROR, TAG, "BLUETOOTH permission no granted")
            errorCounter++
            notifyError()
            stopSelf()
            return START_NOT_STICKY
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        numbAttempts = intent?.getIntExtra(EXTRA_NUMB_ATTEMPTS, DEFAULT_NUMB_ATTEMPTS) ?: DEFAULT_NUMB_ATTEMPTS
        deviceVersion = intent?.getIntExtra(EXTRA_DEVICE_VERSION, UNIDENTIFIED) ?: UNIDENTIFIED
        connect(address)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Utils.log(TAG, "Bonding Service destroyed")
        if(errorCounter == 0) {
            Utils.log(Log.DEBUG, TAG, "Device version: $deviceVersion")
            StateManager.updateState(STATE_READY, "Mini version $deviceVersion connected")
            Preference.putInt(applicationContext, Constants.CURRENT_DEVICE_VERSION, deviceVersion)
        } else {
            Utils.log(Log.DEBUG, TAG, "Device version: $UNIDENTIFIED")
            Preference.putInt(applicationContext, Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED)
        }
        serviceJob.cancel()
    }

    private fun reConnect(address: String?) {
        Utils.log(Log.DEBUG, TAG, "Reconnecting to the device...")
        serviceScope.launch {
            delay(2000)
            connect(address)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(address: String?) {
        Utils.log(Log.DEBUG, TAG, "Connecting to the device...")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled || !BluetoothUtils.isValidBluetoothMAC(address)) {
            Utils.log(Log.ERROR, TAG, "Bluetooth is not enabled or invalid MAC address")
            errorCounter++
            notifyError()
            stopSelf()
            return
        }

        val device = adapter.getRemoteDevice(address)
        if (device == null) {
            Utils.log(Log.ERROR, TAG, "Device is null")
            errorCounter++
            notifyError()
            stopSelf()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.connectGatt(this, false,
                gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false,
                gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false,
                gattCallback)
        }
        Utils.log(Log.DEBUG, TAG, "Connecting to the device...")
    }

    @SuppressWarnings("MissingPermission")
    private fun handleConnectedState(gatt: BluetoothGatt) {
        val bondState = gatt.device.bondState
//        if (bondState == BluetoothDevice.BOND_BONDING) {
//            Utils.log(Log.WARN, TAG, "Waiting for bonding to complete")
//        } else {
            BluetoothUtils.clearServicesCache(gatt)
            serviceScope.launch {
                Utils.log(Log.DEBUG, TAG, "Wait for 2000 millis before service discovery")
                delay(2000)
                startServiceDiscovery(gatt)
            }
//        }
    }

    @SuppressWarnings("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        var result = false
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            serviceScope.launch {
                Utils.log(Log.DEBUG, TAG, "Wait for 1600 millis before service discovery")
                delay(1600)
                result = gatt.discoverServices()
            }
        } else{
            result = gatt.discoverServices()
        }

        if (!result) {
            Utils.log(Log.ERROR, TAG, "Discover Services failed to start")
            errorCounter++
            notifyError()
            gatt.disconnect()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun getDfuControlService(gatt: BluetoothGatt) {
        val dfuControlService = gatt.getService(DFU_CONTROL_SERVICE_UUID)
        if (dfuControlService == null) {
            if (attempts < numbAttempts) {
                Utils.log(Log.WARN, TAG, "Cannot find DFU legacy service. Attempt: $attempts")
                reConnect(gatt.device.address)
                attempts++
                return
            }
            Utils.log(Log.WARN, TAG, "Cannot find DFU legacy service. Attempts: $attempts")
            getSecureDfuService(gatt)
            return
        }

        val dfuControlCharacteristic = dfuControlService.getCharacteristic(DFU_CONTROL_CHARACTERISTIC_UUID)
        if (dfuControlCharacteristic == null) {
            Utils.log(Log.WARN, TAG, "Cannot find DFU legacy characteristic")
            gatt.disconnect()
            return
        }

        deviceVersion = MINI_V1
        gatt.readCharacteristic(dfuControlCharacteristic)
    }

    @SuppressWarnings("MissingPermission")
    private fun getSecureDfuService(gatt: BluetoothGatt) {
        val secureDfuService = gatt.getService(SECURE_DFU_SERVICE_UUID)
        if (secureDfuService == null) {
            Utils.log(Log.WARN, TAG, "Cannot find SECURE_DFU_SERVICE_UUID")
            gatt.disconnect()
            return
        }

        deviceVersion = MINI_V2
        // TODO add to settings
        val device = gatt.device
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Utils.log(Log.WARN, TAG, "Device is not bonded")
            device.createBond()
        }

        gatt.disconnect()
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        BluetoothUtils.clearServicesCache(gatt)
        gatt.close()
        stopSelf()
    }

    private fun notifyError() {
        val message = getString(R.string.flashing_connection_fail)
        StateManager.updateState(STATE_ERROR, message)
    }
}
