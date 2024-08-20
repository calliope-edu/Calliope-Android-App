package cc.calliope.mini.core.service

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.PendingIntent
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Notification.ERROR
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.BluetoothUtils
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.Utils
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

        const val BROADCAST_COMPLETED =
            "cc.calliope.mini.core.service.LegacyDfuService.BROADCAST_COMPLETE"

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
                    Utils.log(Log.WARN, TAG, "Disconnected by device")
                    reConnect(gatt.device.address)
                }
                else -> {
                    if(attempts < numbAttempts) {
                        Utils.log(Log.WARN, TAG, "Connection failed. Attempt: $attempts")
                        reConnect(gatt.device.address)
                        attempts++
                    } else {
                        val message: String = getString(GattStatus.get(status).message)
                        Utils.log(Log.ERROR, TAG, "Connection failed. Attempts: $attempts. Error: $status $message")
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
                Utils.log(Log.WARN, TAG, "Services discovered not success")
            }
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            Utils.log(Log.ASSERT, TAG, "onServiceChanged")
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
                writeCharacteristic(gatt, characteristic)
            } else {
                Utils.log(Log.WARN, TAG, "Characteristic read failed: ${characteristic.uuid}")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == GATT_SUCCESS) {
                Utils.log(Log.DEBUG, TAG, "Flash command written successfully")
                isComplete = true
            } else {
                Utils.log(Log.ERROR, TAG, "Error writing characteristic: $status")
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

    @Suppress("DEPRECATION")
    private fun getParcelableExtra(intent: Intent?, name: String): ResultReceiver? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(name, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(name)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Utils.log(TAG, "Legacy DFU Service started")

        // Get the pending intent from the intent
        resultReceiver = getParcelableExtra(intent,"resultReceiver")

        if (!Permission.isAccessGranted(this, *Permission.BLUETOOTH_PERMISSIONS)) {
                Utils.log(Log.ERROR, TAG, "BLUETOOTH permission no granted")
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

        Utils.log(TAG, "Legacy DFU Service destroyed")
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
            ApplicationStateHandler.updateNotification(ERROR, "Bluetooth adapter is null or not enabled")
            ApplicationStateHandler.updateState(State.STATE_IDLE)
            stopSelf()
            return
        }

        val device = adapter.getRemoteDevice(address)
        if (device == null) {
            Utils.log(Log.ERROR, TAG, "Device is null")
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
            Utils.log(Log.WARN, TAG, "Waiting for bonding to complete")
        } else {
            BluetoothUtils.clearServicesCache(gatt)
            serviceScope.launch {
                Utils.log(Log.DEBUG, TAG, "Wait for 2000 millis before service discovery")
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
                Utils.log(Log.DEBUG, TAG, "Wait for 1600 millis before service discovery")
                delay(1600)
                result = gatt.discoverServices()
            }
        } else {
            result = gatt.discoverServices()
        }

        if (!result) {
            Utils.log(Log.ERROR, TAG, "discoverServices failed to start")
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
            Utils.log(Log.ERROR, TAG, "Cannot find DFU legacy service. Attempts: $attempts")
            ApplicationStateHandler.updateNotification(ERROR, "Cannot find DFU legacy service.")
            ApplicationStateHandler.updateState(State.STATE_IDLE)
            gatt.disconnect()
            return
        }

        val dfuControlCharacteristic = dfuControlService.getCharacteristic(
            DFU_CONTROL_CHARACTERISTIC_UUID
        )
        if (dfuControlCharacteristic == null) {
            Utils.log(Log.ERROR, TAG, "Cannot find DFU legacy characteristic")
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
                Utils.log(Log.DEBUG, TAG, "Writing Flash Command...")
            } else {
                Utils.log(Log.ERROR, TAG, "Error writing characteristic: $res")
            }
        } else {
            characteristic.setValue(1, BluetoothGattCharacteristic.FORMAT_UINT8, 0)
            try {
                Utils.log(Log.DEBUG, TAG, "Writing Flash Command...")
                gatt.writeCharacteristic(characteristic)
            } catch (e: Exception) {
                e.printStackTrace()
                gatt.disconnect()
                Utils.log(Log.ERROR, TAG, "Error writing characteristic: ${e.message}")
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        BluetoothUtils.clearServicesCache(gatt)
        serviceScope.launch {
            Utils.log(Log.DEBUG, TAG, "Wait for 2000 millis before closing the service...")
            delay(2000)
            gatt.close()
            stopSelf()
        }
    }

    private fun sendBroadcast(action: String) {
        val broadcast = Intent(action)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(broadcast)
    }
}
