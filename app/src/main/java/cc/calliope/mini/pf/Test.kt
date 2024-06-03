package cc.calliope.mini.pf

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_CONNECTION_CONGESTED
import android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Log
import android.util.Log.ASSERT
import android.util.Log.DEBUG
import android.util.Log.ERROR
import android.util.Log.INFO
import android.util.Log.WARN
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import cc.calliope.mini.BondingService
import cc.calliope.mini.utils.BluetoothUtils
import cc.calliope.mini.utils.Utils
import java.util.UUID


class Test : Service() {

    companion object {
        const val STATE_STARTED = 0
        const val STATE_CONNECTING = -1
        const val STATE_CONNECTED = -2
        const val STATE_READY = -3
        const val STATE_ATTEMPTING_DFU = -4
        const val STATE_DISCONNECTING = -5
        const val STATE_DISCONNECTED = -6
        const val STATE_REBOOT = -7
        const val STATE_CLOSED = -8
        const val STATE_FAILED = -9
        const val STATE_ERROR = -10

        // TODO: Rename these
        const val ATTEMPT_FILED = -1
        const val ATTEMPT_ENTER_DFU = 0
        const val ATTEMPT_SUCCESS = 1
        const val ATTEMPT_PFL = 2

        const val MINI_V1 = 1
        const val MINI_V2 = 2

        var BLUETOOTH_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else
                arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)

        private val GENERIC_ATTRIBUTE_SERVICE_UUID = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB")
        private val SERVICE_CHANGED_UUID = UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB")
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private val PARTIAL_FLASHING_SERVICE_UUID = UUID.fromString("E97DD91D-251D-470A-A062-FA1922DFA9A8")
        private val PARTIAL_FLASHING_CHARACTERISTIC_UUID = UUID.fromString("E97D3B10-251D-470A-A062-FA1922DFA9A8")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val REGION_INFO_COMMAND = 0x00.toByte()
        private const val FLASH_COMMAND = 0x01.toByte()
        private const val STATUS = 0xEE.toByte()
        private const val MODE_APPLICATION = 0x01.toByte()
        private const val MODE_PAIRING = 0x00.toByte()

        private const val REGION_DAL = 1
    }

    private val tag = "PartialFlashingService"
    private val binder = LocalBinder()
    private lateinit var serviceHandler: ServiceHandler

    private val lock = Object()
    private val bondLock = Object()

    private var deviceAddress: String = ""
    private var filePath: String = ""
    private var hardwareType: Int = 1

    private var connectionState: Int = STATE_DISCONNECTED

    val progressData: MutableLiveData<Int> = MutableLiveData()
    val serviceState: MutableLiveData<Int> = MutableLiveData()

    private var bondState: Int = BOND_NONE

    private lateinit var partialFlashingCharacteristic: BluetoothGattCharacteristic

    private var descriptorWriteSuccess = false
    private var statusRequestSuccess = false

    inner class LocalBinder : Binder() {
        fun getService(): Test = this@Test
    }

    // Handler that receives messages from the thread
    @SuppressWarnings("MissingPermission")
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            log(DEBUG, "Service handling message: ${msg.arg1}")

            // Check if the permissions are granted
            if (isPermissionGranted(*BLUETOOTH_PERMISSIONS)) {
                val status: Int = attemptPartialFlashing()
                when (status) {
                    ATTEMPT_ENTER_DFU -> {
                        log(DEBUG, "Entering DFU mode...")
                        updateState(STATE_ATTEMPTING_DFU)
                    }

                    ATTEMPT_FILED -> {
                        log(ERROR, "Partial flashing failed")
                        updateState(STATE_FAILED)
                    }

                    ATTEMPT_SUCCESS -> {
                        log(DEBUG, "Partial flashing successful")
                        updateState(STATE_DISCONNECTING)
                    }
                }
            } else {
                log(ERROR, "No Permission Granted")
                updateState(STATE_ERROR)
            }
            waitForDisconnect()
            log(DEBUG, "Stopping service...")
            // TODO: Can't stop until binding is finished
            stopSelf(msg.arg1)
        }
    }



    private val bondStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

            if (device == null || device.getAddress() != deviceAddress) {
                return
            }

            val action = intent.action ?: return

            // Take action depending on new bond state
            if (action == ACTION_BOND_STATE_CHANGED) {
                bondState = intent.getIntExtra(EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                when (bondState) {
                    BOND_BONDING -> {
                        log(DEBUG, "Bonding started...")
                    }

                    BOND_BONDED, BOND_NONE -> {
                        log(DEBUG, "Bonding " + (bondState == BOND_BONDED))
                        synchronized(bondLock) { bondLock.notifyAll() }
                    }
                }
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            log(ASSERT, "onConnectionStateChange: $newState")

            // TODO: Now we here
//            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION){
//                attemptPartialFlashing()
//                return
//            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // If the connection was successful
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // Update the state
                        updateState(STATE_CONNECTED)

                        // Get bond state
                        bondState = gatt.device.getBondState()

                        if (bondState == BOND_BONDING) {
                            // Wait until bonding is finished
                            waitForBonding()
                        } else {
                            /* Taken from Nordic. See reasoning here: https://github.com/NordicSemiconductor/Android-DFU-Library/blob/e0ab213a369982ae9cf452b55783ba0bdc5a7916/dfu/src/main/java/no/nordicsemi/android/dfu/DfuBaseService.java#L888 */
                            if (bondState == BOND_BONDED) {
                                log(DEBUG, "Wait for service changed...")
                                synchronized(bondLock) {
                                    try {
                                        log(DEBUG, "Wait for 1600 millis")
                                        bondLock.wait(1600)
                                    } catch (e: InterruptedException) {
                                        log(ERROR, "Sleeping interrupted, $e")
                                    }
                                }
                                // NOTE: This also works with shorter waiting time. The gatt.discoverServices() must be called after the indication is received which is
                                // about 600ms after establishing connection. Values 600 - 1600ms should be OK.
                            }

                            if(hardwareType == MINI_V1) {
                                log(DEBUG, "Hardware type 1, clearing cache...")
                                clearServicesCache(gatt)
                                waitFor(2000)
                            } else {
                                log(DEBUG, "Hardware type 2")
                            }

                            val result = gatt.discoverServices()
                            if (!result) {
                                log(ERROR, "DiscoverServices failed to start")
                                updateState(STATE_DISCONNECTING)
                                gatt.disconnect()
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log(DEBUG, "Disconnected")
                        updateState(STATE_DISCONNECTED)
                    }
                }
            } else if (status == GATT_INSUFFICIENT_AUTHORIZATION) {
                log(DEBUG, "Insufficient authorization")
            }
            else {
                log(ERROR, "Connection error: $status")
                updateState(STATE_ERROR)
            }

            // Clear the lock
            log(INFO, "Unlocking from onConnectionStateChange...")
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            log(ASSERT, "onServicesDiscovered: $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateState(STATE_READY)
            } else {
                log(ERROR, "Services discovered error: $status")
                updateState(STATE_ERROR)
            }

            // Clear the lock
            log(INFO, "Unlocking from onServicesDiscovered...")
            synchronized(lock) { lock.notifyAll() }
        }

        // API 31 Android 12
        override fun onServiceChanged(gatt: BluetoothGatt) {
            super.onServiceChanged(gatt)
            log(DEBUG,"onServiceChanged")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            log(ASSERT, "onDescriptorWrite: $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorWriteSuccess = true
            } else {
                updateState(STATE_FAILED)
                gatt.disconnect()
            }
            // Notify waiting thread
            log(INFO, "Unlocking from onDescriptorWrite...")
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            log(ASSERT, "onCharacteristicWrite: $status")
            // Notify waiting thread
            log(INFO, "Unlocking from onCharacteristicWrite...")
            synchronized(lock) { lock.notifyAll() }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            log(ASSERT, "onCharacteristicChanged: ${value[0]}")

            val command = value[0]
            when (command) {
//                REGION_INFO_COMMAND -> {
//                    val hash = Arrays.copyOfRange(value, 10, 18)
//                    if (value[1].toInt() == REGION_DAL) {
//                        dalHash = MyPartialFlashingBaseService.bytesToHex(hash)
//                        log(Log.VERBOSE, "Hash: $dalHash")
//                        hashRequestSuccess = true
//                    }
//                }
//
//                FLASH_COMMAND -> {
//                    packetState = value[1]
//                }

                STATUS -> {
                    statusRequestSuccess = true
                    // Current board mode
                    val mode = value[2]
                    log(DEBUG, if (mode == MODE_APPLICATION) "Device in " + "application" else "pairing" + " mode")

                    // TODO: Now we here


                    if (mode == MODE_APPLICATION) {
                        //Reset (0x00 for Pairing Mode, 0x01 for Application Mode)
                        log(DEBUG, "Resetting the device...")
                        writeCharacteristic(gatt, 0xFF.toByte(), MODE_PAIRING)
                    }
                }
            }
            log(INFO, "Unlocking from onCharacteristicChanged...")
            synchronized(lock) { lock.notifyAll() }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work doesn't disrupt our UI.
        val thread = HandlerThread(
            "ServiceStartArguments",
            Process.THREAD_PRIORITY_BACKGROUND
        ).apply {
            start()
        }

        // Get the HandlerThread's Looper and use it for our Handler
        serviceHandler = ServiceHandler(thread.looper)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onCreate()

        log(INFO, "Service starting, startId: $startId")

        // Register the broadcast receiver for bond state changes
        registerReceiver(bondStateReceiver, IntentFilter(ACTION_BOND_STATE_CHANGED))

        // Get the device address and file path from the intent
        deviceAddress = intent.getStringExtra("deviceAddress") ?: ""
        filePath = intent.getStringExtra("filePath") ?: ""
        hardwareType = intent.getIntExtra("hardwareType", 1)

        log(DEBUG, "Device address: $deviceAddress")
        log(DEBUG, "File path: $filePath")
        log(DEBUG, "Hardware type: $hardwareType")

        // For each start request, send a message to start a job and deliver the
        // start ID so we know which request we're stopping when we finish the job
        serviceHandler.obtainMessage().apply {
            arg1 = startId
            serviceHandler.sendMessage(this)
        }

        // Update the state to started
        updateState(STATE_STARTED)

        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        log(INFO, "Service destroyed")
        updateState(STATE_CLOSED)
        unregisterReceiver(bondStateReceiver)
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(): BluetoothGatt? {
        log(DEBUG, "Connecting to the device...")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled || !BluetoothUtils.isValidBluetoothMAC(deviceAddress)) {
            Utils.log(
                ERROR,
                BondingService.TAG,
                "Bluetooth is not enabled or invalid MAC address"
            )
            return null
        }

        val device = adapter.getRemoteDevice(deviceAddress)
        if (device == null) {
            Utils.log(ERROR, BondingService.TAG, "Device is null")
            return null
        }

        updateState(STATE_CONNECTING)
        bondState = device.getBondState()

        val gatt: BluetoothGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(
                    this, false,
                    gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            } else {
                device.connectGatt(
                    this, false,
                    gattCallback
                )
            }

        // We have to wait until the device is connected and services are discovered
        // Connection error may occur as well.
        try {
            log(INFO, "Locking for connection...")
            synchronized(lock) {
                while (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED) lock.wait()
            }
        } catch (e: InterruptedException) {
            log(ERROR, "Sleeping interrupted $e")
        }
        return gatt
    }

    private fun isPermissionGranted(vararg permissions: String): Boolean {
        for (permission in permissions) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext, permission
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }
        return true
    }

    private fun attemptPartialFlashing(): Int {
        log(DEBUG, "Attempting partial flashing...")

        // Connect to the device
        val gatt = connect()
        if (gatt == null || connectionState != STATE_READY) {
            return ATTEMPT_FILED
        }
        log(DEBUG, "Connected and ready")

        if (!isPartialFlashingServiceAvailable(gatt)) {
            return ATTEMPT_ENTER_DFU
        }
        log(DEBUG, "Partial flashing service available")

        if (!sendStatusRequest(gatt)) {
            return ATTEMPT_FILED
        }
        log(DEBUG, "Status request sent")

        // TODO: Now we here



        log(WARN, "SUCCESSFUL")
        return ATTEMPT_SUCCESS
    }

    @SuppressWarnings("MissingPermission")
    private fun isPartialFlashingServiceAvailable(gatt: BluetoothGatt): Boolean {
        log(DEBUG, "Checking if the flashing services is available...")
        val service = gatt.getService(PARTIAL_FLASHING_SERVICE_UUID)
        if (service == null) {
            log(WARN, "Partial flashing service not found")
            return false
        }

        val characteristic = service.getCharacteristic(PARTIAL_FLASHING_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            log(WARN, "Partial flashing characteristic not found")
            return false
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            log(WARN, "Set characteristic notification failed")
            return false
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (!writeDescriptor(gatt, descriptor)) {
            log(WARN, "Write descriptor failed")
            return false
        }

        try {
            log(INFO, "Locking for descriptor write...")
            synchronized(lock) { while (!descriptorWriteSuccess) lock.wait() }
        } catch (e: InterruptedException) {
            log(ERROR, "Sleeping interrupted $e")
        }

        partialFlashingCharacteristic = characteristic
        return true
    }

    private fun sendStatusRequest(gatt: BluetoothGatt): Boolean {
        log(DEBUG, "Send status request...")
        val res: Boolean = writeCharacteristic(gatt, 0xEE.toByte())
        try {
            log(INFO, "Locking for status request...")
            synchronized(lock) { while (!statusRequestSuccess) (lock as Object).wait() }
        } catch (e: InterruptedException) {
            log(ERROR, "Sleeping interrupted $e")
        }
        return res
    }

    @SuppressWarnings("MissingPermission")
    fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun writeCharacteristic(gatt: BluetoothGatt, vararg data: Byte): Boolean {
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(partialFlashingCharacteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            partialFlashingCharacteristic.writeType = writeType
            partialFlashingCharacteristic.value = data
            gatt.writeCharacteristic(partialFlashingCharacteristic)
        }

    }

    private fun waitFor(millis: Long) {
        synchronized(lock) {
            try {
                log(DEBUG, "Wait for $millis millis")
                lock.wait(millis)
            } catch (e: InterruptedException) {
                log(ERROR, "Sleeping interrupted, $e")
            }
        }
    }

    private fun waitForDisconnect() {
        log(DEBUG, "Waiting until disconnecting is finished...")
        try {
            log(INFO, "Locking for disconnect...")
            synchronized(lock) {
                while (connectionState != STATE_DISCONNECTED) lock.wait()
            }
        } catch (e: InterruptedException) {
            log(ERROR, "Sleeping interrupted $e")
        }
    }

    private fun waitForBonding() {
        log(WARN, "Waiting until bonding is finished...")
        try {
            synchronized(bondLock) {
                while (bondState == BOND_BONDING)
                    bondLock.wait()
            }
        } catch (e: InterruptedException) {
            log(ERROR, "Sleeping interrupted, $e")
        }
    }


    private fun clearServicesCache(gatt: BluetoothGatt) {
        try {
            val refresh = gatt.javaClass.getMethod("refresh")
            val success = refresh.invoke(gatt) as Boolean
            Utils.log(DEBUG, BluetoothUtils.TAG, "Refreshing result: $success")
        } catch (e: Exception) {
            Utils.log(ERROR, BluetoothUtils.TAG, "An exception occurred while refreshing device. $e"
            )
        }
    }

    private fun updateProgress(progress: Int) {
        progressData.postValue(progress)
    }

    private fun updateState(state: Int) {
        connectionState = state
        serviceState.postValue(state)
    }

    private fun log(priority: Int, message: String) {
        Log.println(priority, tag, "### " + Thread.currentThread().id + " # $message")
    }
}
