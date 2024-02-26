package cc.calliope.mini

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import cc.calliope.mini.service.DfuControlService
import cc.calliope.mini.service.GattStatus
import cc.calliope.mini.utils.Preference
import cc.calliope.mini.utils.StaticExtras
import cc.calliope.mini.utils.Utils
import cc.calliope.mini.utils.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

open class BondingService : Service() {
    companion object {
        const val TAG = "MyService"
        const val EXTRA_DEVICE_ADDRESS = "my_service.device_address"

        private val DEVICE_FIRMWARE_UPDATE_CONTROL_SERVICE_UUID =
            UUID.fromString("E95D93B0-251D-470A-A062-FA1922DFA9A8")
        private val DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID =
            UUID.fromString("E95D93B1-251D-470A-A062-FA1922DFA9A8")
    }

    private var deviceAddress: String? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val bondStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address != deviceAddress) return

            val action = intent.action ?: return

            if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDING -> Utils.log(Log.WARN, TAG, "Start pairing...")
                    BluetoothDevice.BOND_BONDED -> Utils.log(Log.WARN, TAG, "Paired")
                    BluetoothDevice.BOND_NONE -> {
                        Utils.log(Log.WARN, TAG, "Unpaired")
                        connect(device)
                    }
                }
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Utils.log(Log.DEBUG, TAG, "onConnectionStateChange: $newState")
            if (status == BluetoothGatt.GATT_SUCCESS || status == DfuControlService.GATT_DISCONNECTED_BY_DEVICE) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        handleConnectedState(gatt)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        stopService(gatt)
                    }
                }
            } else {
                val message: String = getString(GattStatus.get(status).message)
                Utils.log(Log.ERROR, TAG, "Error: $status $message")
                stopService(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Utils.log(Log.DEBUG, TAG, "onServicesDiscovered: $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                getDfuControlService(gatt)
            } else {
                gatt.disconnect()
                Utils.log(Log.WARN, TAG, "Services discovered not success")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Utils.log(Log.DEBUG, TAG, "Characteristic read: ${characteristic.uuid}")
            } else {
                Utils.log(Log.WARN, TAG, "Characteristic read failed: ${characteristic.uuid}")
            }
            gatt.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Utils.log(TAG, "Service created")
        registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Utils.log(TAG, "Service started")

        deviceAddress = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)

        if (Version.VERSION_S_AND_NEWER
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            Utils.log(Log.ERROR, TAG, "BLUETOOTH permission no granted")
            stopSelf()
            return START_NOT_STICKY
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (!adapter.isEnabled || !isValidBluetoothMAC(deviceAddress)){
            stopSelf()
            return START_NOT_STICKY
        }

        val device = adapter.getRemoteDevice(deviceAddress)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            removeBond(device)
        } else {
            connect(device)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Utils.log(TAG, "Service destroyed")
        unregisterReceiver(bondStateReceiver)
        serviceJob.cancel()
    }

    private fun removeBond(device: BluetoothDevice): Boolean {
        Utils.log(Log.WARN, TAG, "Unpairing device...")
        return try {
            val removeBondMethod = device.javaClass.getMethod("removeBond")
            val result = removeBondMethod.invoke(device) as Boolean
            Log.d("removeBond", "Unpairing successful: $result")
            result
        } catch (e: Exception) {
            Log.e("removeBond", "Exception occurred during unpairing", e)
            false
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(device: BluetoothDevice?) {
        Utils.log(Log.DEBUG, TAG, "Connecting to the device...")
        if (device == null) {
            Utils.log(Log.ERROR, TAG, "Device is null")
            stopSelf()
            return
        }

        if (Version.VERSION_O_AND_NEWER) {
            device.connectGatt(this, false,
                gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK)
        } else if (Version.VERSION_M_AND_NEWER) {
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
            Utils.log(Log.WARN, TAG, "waiting for bonding to complete")
        } else {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N && bondState == BluetoothDevice.BOND_BONDED) {
                serviceScope.launch {
                    Utils.log(Log.DEBUG, TAG, "Wait for 1600 millis before service discovery")
                    delay(1600)
                    startServiceDiscovery(gatt)
                }
            } else {
                startServiceDiscovery(gatt)
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        val result = gatt.discoverServices()
        if (!result) {
            Utils.log(Log.ERROR, TAG, "discoverServices failed to start")
        }
    }

    @SuppressWarnings("MissingPermission")
    fun getDfuControlService(gatt: BluetoothGatt) {
        val dfuControlService = gatt.getService(DEVICE_FIRMWARE_UPDATE_CONTROL_SERVICE_UUID)
        if (dfuControlService == null) {
            Log.w(TAG, "Cannot find DEVICE_FIRMWARE_UPDATE_CONTROL_SERVICE_UUID")
            return
        }

        val dfuControlCharacteristic = dfuControlService.getCharacteristic(DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID)
        if (dfuControlCharacteristic == null) {
            Log.w(TAG, "Cannot find DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID")
            return
        }

        gatt.readCharacteristic(dfuControlCharacteristic)
    }

    private fun isValidBluetoothMAC(macAddress: String?): Boolean {
        if (macAddress == null) {
            Utils.log(Log.ERROR, TAG, "MAC address is null")
            return false
        }

        val regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"
        return if (macAddress.matches(regex.toRegex())) {
            Utils.log(Log.INFO, TAG, "Valid Bluetooth MAC address: $macAddress")
            true
        } else {
            Utils.log(Log.INFO, TAG, "Invalid Bluetooth MAC address: $macAddress")
            false
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        clearServicesCache(gatt)
        gatt.close()
        stopSelf()
    }

    private fun clearServicesCache(gatt: BluetoothGatt) {
        try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val success = refresh.invoke(gatt) as Boolean
            Utils.log(Log.DEBUG, TAG, "Refreshing result: $success")
        } catch (e: Exception) {
            Utils.log(Log.ERROR, TAG, "An exception occurred while refreshing device. $e")
        }
    }
}
