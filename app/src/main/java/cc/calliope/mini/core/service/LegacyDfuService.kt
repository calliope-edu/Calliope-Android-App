package cc.calliope.mini.core.service

import android.app.Activity.RESULT_OK
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.util.Log
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Notification.ERROR
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class LegacyDfuService : Service() {
    companion object {
        const val TAG = "LegacyDfuService"
        const val GATT_DISCONNECTED_BY_DEVICE = 19
        const val EXTRA_DEVICE_ADDRESS = Constants.CURRENT_DEVICE_ADDRESS
        const val EXTRA_NUMB_ATTEMPTS = Constants.EXTRA_NUMB_ATTEMPTS
        const val DEFAULT_NUMB_ATTEMPTS = 1

        private val DFU_CONTROL_SERVICE_UUID = Constants.DFU_CONTROL_SERVICE_UUID
        private val DFU_CONTROL_CHARACTERISTIC_UUID = Constants.DFU_CONTROL_CHARACTERISTIC_UUID
    }

    private var isComplete = false
    private var attempts = 0
    private var numbAttempts = DEFAULT_NUMB_ATTEMPTS
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var resultReceiver: ResultReceiver? = null

    @SuppressWarnings("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (status) {
                GATT_SUCCESS -> {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> handleConnectedState(gatt)
                        BluetoothProfile.STATE_DISCONNECTED -> stopService(gatt)
                    }
                }
                GATT_DISCONNECTED_BY_DEVICE -> {
                    Log.w(TAG, "Disconnected by device")
                    reConnect(gatt.device.address)
                }
                else -> {
                    if(attempts < numbAttempts) {
                        Log.w(TAG, "Connection failed. Attempt: $attempts")
                        reConnect(gatt.device.address)
                        attempts++
                    } else {
                        val message: String = getString(GattStatusUser.get(status).message)
                        Log.e(TAG, "Connection failed. Attempts: $attempts. Error: $status $message")
                        stopService(gatt)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == GATT_SUCCESS) {
                getDfuControlService(gatt)
            } else {
                gatt.disconnect()
                Log.w(TAG, "Services discovered not success")
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
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
                writeCharacteristic(gatt, characteristic)
            } else {
                Log.w(TAG, "Characteristic read failed: ${characteristic.uuid}")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == GATT_SUCCESS) {
                Log.d(TAG, "Flash command written successfully")
                isComplete = true
            } else {
                Log.e(TAG, "Error writing characteristic: $status")
            }
            gatt.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtra(intent: Intent?, name: String): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(name, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(name)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Legacy DFU Service started")

        // Get the pending intent from the intent
        resultReceiver = getParcelableExtra(intent,"resultReceiver")

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
                Log.e(TAG, "BLUETOOTH permission no granted")
                stopSelf()
                return START_NOT_STICKY
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        numbAttempts = intent?.getIntExtra(EXTRA_NUMB_ATTEMPTS, DEFAULT_NUMB_ATTEMPTS) ?: DEFAULT_NUMB_ATTEMPTS
        connect(address)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()

        val bundle = Bundle()
        bundle.putBoolean("result", isComplete)
        resultReceiver?.send(RESULT_OK, bundle)

        Log.d(TAG, "Legacy DFU Service destroyed")
    }

    private fun reConnect(address: String?) {
        Log.d(TAG, "Reconnecting to the device...")
        serviceScope.launch {
            delay(2000)
            connect(address)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(address: String?) {
        Log.d(TAG, "Connecting to the device...")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled || !BluetoothUtils.isValidBluetoothMAC(address)) {
            ApplicationStateHandler.updateNotification(ERROR, "Bluetooth adapter is null or not enabled")
            ApplicationStateHandler.updateState(State.STATE_IDLE)
            stopSelf()
            return
        }

        val device = adapter.getRemoteDevice(address)
        if (device == null) {
            Log.e(TAG, "Device is null")
            ApplicationStateHandler.updateNotification(ERROR, "Device is null")
            stopSelf()
            return
        }

        ApplicationStateHandler.updateState(State.STATE_BUSY)

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
    }

    @SuppressWarnings("MissingPermission")
    private fun handleConnectedState(gatt: BluetoothGatt) {
        val bondState = gatt.device.bondState
        if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.w(TAG, "Waiting for bonding to complete")
        } else {
            BluetoothUtils.clearServicesCache(gatt)
            serviceScope.launch {
                Log.d(TAG, "Wait for 2000 millis before service discovery")
                delay(2000)
                startServiceDiscovery(gatt)
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        var result = false
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            serviceScope.launch {
                Log.d(TAG, "Wait for 1600 millis before service discovery")
                delay(1600)
                result = gatt.discoverServices()
            }
        } else {
            result = gatt.discoverServices()
        }

        if (!result) {
            Log.e(TAG, "discoverServices failed to start")
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun getDfuControlService(gatt: BluetoothGatt) {
        val dfuControlService = gatt.getService(DFU_CONTROL_SERVICE_UUID)
        if (dfuControlService == null) {
            if (attempts < numbAttempts) {
                Log.w(TAG, "Cannot find DFU legacy service. Attempt: $attempts")
                reConnect(gatt.device.address)
                attempts++
                return
            }
            Log.e(TAG, "Cannot find DFU legacy service. Attempts: $attempts")
            ApplicationStateHandler.updateNotification(ERROR, "Cannot find DFU legacy service.")
            ApplicationStateHandler.updateState(State.STATE_IDLE)
            gatt.disconnect()
            return
        }

        val dfuControlCharacteristic = dfuControlService.getCharacteristic(
            DFU_CONTROL_CHARACTERISTIC_UUID
        )
        if (dfuControlCharacteristic == null) {
            Log.e(TAG, "Cannot find DFU legacy characteristic")
            ApplicationStateHandler.updateNotification(ERROR, "Cannot find DFU legacy characteristic.")
            ApplicationStateHandler.updateState(State.STATE_IDLE)
            gatt.disconnect()
            return
        }

        gatt.readCharacteristic(dfuControlCharacteristic)
    }

    @SuppressWarnings("MissingPermission")
    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val value = byteArrayOf(1)
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val res = gatt.writeCharacteristic(characteristic, value, writeType)

            if (res == BluetoothStatusCodes.SUCCESS) {
                Log.d(TAG, "Writing Flash Command...")
            } else {
                Log.e(TAG, "Error writing characteristic: $res")
            }
        } else {
            characteristic.setValue(1, BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            try {
                Log.d(TAG, "Writing Flash Command...")
                gatt.writeCharacteristic(characteristic)
            } catch (e: Exception) {
                e.printStackTrace()
                gatt.disconnect()
                Log.e(TAG, "Error writing characteristic: ${e.message}")
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        BluetoothUtils.clearServicesCache(gatt)
        serviceScope.launch {
            Log.d(TAG, "Wait for 2000 millis before closing the service...")
            delay(2000)
            gatt.close()
            stopSelf()
        }
    }
}
