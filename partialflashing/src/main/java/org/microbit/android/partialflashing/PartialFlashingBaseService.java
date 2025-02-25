package org.microbit.android.partialflashing;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.IntentService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * A class to communicate with and flash the micro:bit without having to transfer the entire HEX file
 * Created by samkent on 07/11/2017.
 *
 * (c) 2017 - 2021, Micro:bit Educational Foundation and contributors
 *
 * SPDX-License-Identifier: MIT
 */

// A service that interacts with the BLE device via the Android BLE API.
public abstract class PartialFlashingBaseService extends IntentService {
    private final static String TAG = PartialFlashingBaseService.class.getSimpleName();
    private static boolean DEBUG = false;

    public void logi(String message) {
        if (DEBUG) {
            Log.i(TAG, "### " + Thread.currentThread().getId() + " # " + message);
        }
    }

    // ================================================================
    // INTENT SERVICE

    public static final String BROADCAST_ERROR = "org.microbit.android.partialflashing.broadcast.BROADCAST_ERROR";
    public static final String BROADCAST_ACTION = "org.microbit.android.partialflashing.broadcast.BROADCAST_ACTION";
    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
    public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";
    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";
    public static final String BROADCAST_PF_ABORTED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ABORTED";
    public static final String EXTRA_ACTION = "org.microbit.android.partialflashing.extra.EXTRA_ACTION";
    public static final int ACTION_ABORT = 0;
    public static final String EXTRA_DATA = "org.microbit.android.partialflashing.extra.EXTRA_DATA";
    public static final int ERROR_CONNECT = 1;
    public static final int ERROR_RECONNECT = 2;
    public static final int ERROR_DFU_MODE = 3;
    public static final int ERROR_BONDED = 4;
    public static final int ERROR_BROKEN = 5;

    private boolean abortReceived = false;
    private Boolean working = false;
    private boolean wasNotBonded = false;

    protected abstract Class<? extends Activity> getNotificationTarget();

    public PartialFlashingBaseService() {
        super(TAG);
    }

    /* Receive updates on user interaction */
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            logi("Received Broadcast: " + intent.toString());
            String action = intent.getAction();

            if (PartialFlashingBaseService.BROADCAST_ACTION.equals(action)) {
                int extra = intent.getIntExtra(EXTRA_ACTION, -1);
                switch (extra) {
                    case ACTION_ABORT:
                        abortReceived = true;
                        // Clear locks
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                        break;
                    default:
                        break;
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                if (!working) {
                    return;
                }
                if (mBluetoothGatt == null) {
                    return;
                }
                if (mBluetoothGatt.getDevice() == null) {
                    return;
                }
                if (mBluetoothGatt.getDevice().getAddress() == null) {
                    return;
                }

                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) {
                    Log.e(TAG, "bondStateReceiver - no device");
                    return;
                }
                final String address = device.getAddress();
                if (address == null) {
                    Log.e(TAG, "bondStateReceiver - no address");
                    return;
                }
                final int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                logi("bondStateReceiver -" + " address = " + address + " state = " + state + " prevState = " + prevState);
                // Check the changed device is the one we are trying to pair
                if (!address.equals(mBluetoothGatt.getDevice().getAddress())) {
                    return;
                }
                if (state != BluetoothDevice.BOND_BONDED && prevState != BluetoothDevice.BOND_BONDED) {
                    wasNotBonded = true;
                }
                switch (state) {
                    case BluetoothDevice.BOND_BONDED:
                        mConnectionState = STATE_BONDED_DISCONNECT;
                        mWaitingForBonding = false;
                        // Clear locks
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                        break;
                    case BluetoothDevice.BOND_BONDING:
                        mWaitingForBonding = true;
                        break;
                    case BluetoothDevice.BOND_NONE:
                        break;
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        DEBUG = isDebug();

        logi("onCreate");

        abortReceived = false;

        // Create intent filter and add to Local Broadcast Manager so that we can use an Intent to
        // start the Partial Flashing Service

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PartialFlashingBaseService.BROADCAST_ACTION);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(broadcastReceiver, intentFilter);

        initialize();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logi("onDestroy");
        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.unregisterReceiver(broadcastReceiver);
    }

    private void sendProgressBroadcast(final int progress) {
        logi("Sending progress broadcast: " + progress + "%");
        final Intent broadcast = new Intent(BROADCAST_PROGRESS);
        broadcast.putExtra(EXTRA_PROGRESS, progress);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastStart() {
        logi("Sending progress broadcast start");
        final Intent broadcast = new Intent(BROADCAST_START);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastComplete() {
        logi("Sending progress broadcast complete");
        final Intent broadcast = new Intent(BROADCAST_COMPLETE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        logi("onHandleIntent");

        final String filePath = intent.getStringExtra("filepath");
        final String deviceAddress = intent.getStringExtra("deviceAddress");
        final int hardwareType = intent.getIntExtra("hardwareType", 1);
        final boolean pf = intent.getBooleanExtra("pf", true);

        partialFlash(filePath, deviceAddress, pf);

        checkAbort();
        logi("onHandleIntent END");
    }

    /**
     * Initializes bluetooth adapter
     *
     * @return <code>true</code> if initialization was successful
     */
    private boolean initialize() {
        logi("initialize");
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return false;
        }

        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            return false;
        }

        return true;
    }

    protected boolean isDebug() {
        return false;
    }

    // ================================================================
    // PARTIAL FLASH

    public static final UUID PARTIAL_FLASH_CHARACTERISTIC = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8");
    public static final UUID PARTIAL_FLASHING_SERVICE = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8");
    public static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";
    public static final String UPY_MAGIC = ".*FE307F59.{16}9DD7B1C1.*";
    public static final String UPY_MAGIC1 = "FE307F59";
    public static final String UPY_MAGIC2 = "9DD7B1C1";

    private static final UUID NORDIC_DFU_SERVICE = UUID.fromString("00001530-1212-EFDE-1523-785FEABCD123");
    private static final UUID MICROBIT_DFU_SERVICE = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_SERVICE = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    private static final UUID MICROBIT_DFU_CHARACTERISTIC = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8");

    // values for writeCharacteristic
    private static final int WITH_RESPONSE = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    private static final int NO_RESPONSE = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    private final static int BLE_PENDING = -1;
    private final static int BLE_ERROR_UNKNOWN = Integer.MAX_VALUE;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt = null;
    private int mConnectionState = STATE_DISCONNECTED;
    private boolean mWaitingForServices = false;
    private boolean mWaitingForBonding = false;

    private boolean descriptorWriteSuccess = false;
    BluetoothGattDescriptor descriptorRead = null;
    boolean descriptorReadSuccess = false;
    byte[] descriptorValue = null;

    private int onWriteCharacteristicStatus = BLE_PENDING;

    BluetoothGattService pfService;
    BluetoothGattCharacteristic partialFlashCharacteristic;

    private final Object lock = new Object();
    private final Object region_lock = new Object();

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte) 0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte) 0xAA;
    private static final byte PACKET_STATE_COMPLETE_FLASH = (byte) 0xCF;

    private byte packetState = PACKET_STATE_WAITING;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_DISCOVERED = 3;
    private static final int STATE_READY = 4;
    private static final int STATE_BONDED_DISCONNECT = 5;
    private static final int STATE_BONDED = 6;
    private static final int STATE_ERROR = 7;
    private static final int STATE_RETRY = 8;

    private static final UUID GENERIC_ATTRIBUTE_SERVICE_UUID = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB");
    private static final UUID SERVICE_CHANGED_UUID = UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Regions
    private static final int REGION_SD = 0;
    private static final int REGION_DAL = 1;
    private static final int REGION_MAKECODE = 2;

    // DAL Hash
    private boolean python = false;
    private String dalHash;
    private String fileHash;
    private long code_startAddress = 0;
    private long code_endAddress = 0;

    // Partial Flashing Commands
    private static final byte REGION_INFO_COMMAND = 0x0;
    private static final byte FLASH_COMMAND = 0x1;

    // Microbit Type
    private final int MICROBIT_V1 = 1;
    private final int MICROBIT_V2 = 2;
    int hardwareType = MICROBIT_V1;

    // Partial Flashing Return Values
    private static final int PF_SUCCESS = 0x0;
    private static final int PF_ATTEMPT_DFU = 0x1;
    private static final int PF_FAILED = 0x2;

    @SuppressLint("MissingPermission")
    private void partialFlash(final String filePath, final String deviceAddress, final boolean pf) {
        logi("partialFlash");

        for (int i = 0; i < 3; i++) {
            mBluetoothGatt = connect(deviceAddress);
            if (abortReceived)
                return;
            if (mBluetoothGatt != null)
                break;
            if (mConnectionState == STATE_ERROR)
                break;
            if (mConnectionState == STATE_BONDED)
                break;
            if (mConnectionState == STATE_CONNECTING)
                break;
        }

        if (mBluetoothGatt == null) {
            logi("Failed to connect");
            logi("Send Intent: BROADCAST_ERROR");
            final Intent broadcast = new Intent(BROADCAST_ERROR);
            if (mConnectionState == STATE_BONDED) {
                broadcast.putExtra(EXTRA_DATA, ERROR_BONDED);
            } else {
                broadcast.putExtra(EXTRA_DATA, ERROR_CONNECT);
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }

        logi("Connected");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (serviceChangedCharacteristic() != null) {
                if (!cccEnabled(serviceChangedCharacteristic(), false)) {
                    if (stateIsError()) {
                        return;
                    }
                    // Only seem to get here with V1
                    // After this, the refresh function is never called
                    // But it doesn't seem to work in Android 8
                    cccEnable(serviceChangedCharacteristic(), false);
                    if (stateIsError()) {
                        return;
                    }
                    logi("Reconnect");
                    disconnectAndClose();
                    if (abortReceived) return;

                    mBluetoothGatt = connect(deviceAddress);
                    if (abortReceived) return;
                    if (mBluetoothGatt == null) {
                        logi("Failed to connect");
                        logi("Send Intent: BROADCAST_ERROR");
                        final Intent broadcast = new Intent(BROADCAST_ERROR);
                        broadcast.putExtra(EXTRA_DATA, ERROR_RECONNECT);
                        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
                        return;
                    }
                    if (!cccEnabled(serviceChangedCharacteristic(), false)) {
                        if (stateIsError()) {
                            return;
                        }
                        cccEnable(serviceChangedCharacteristic(), false);
                        if (stateIsError()) {
                            return;
                        }
                    }
                }
            }
        }

        boolean isV1 = connectedHasV1Dfu();
        if (isV1) {
            refreshV1ForMicroBitDfu();
        }

        if (abortReceived) return;
        if (stateIsError()) {
            return;
        }

        int pfResult = PF_ATTEMPT_DFU;
        if (pf) {
            logi("Trying to partial flash");
            if (partialFlashCharacteristicCheck()) {
                pfResult = attemptPartialFlash(filePath);
            }
        }

        String action = "";
        int extra = 0;

        switch (pfResult) {
            case PF_FAILED: {
                // Partial flashing started but failed. Need to PF or USB flash to fix
                logi("Partial flashing failed");
                logi("Send Intent: BROADCAST_PF_FAILED");
                action = BROADCAST_PF_FAILED;
                break;
            }
            case PF_ATTEMPT_DFU: {
                logi("Attempt DFU");
                action = BROADCAST_PF_ATTEMPT_DFU;
                // If v1 we need to switch the DFU mode
                if (isV1) {
                    if (!enterDFUModeV1()) {
                        logi("Failed to enter DFU mode");
                        action = BROADCAST_ERROR;
                        extra = ERROR_DFU_MODE;
                    }
                }
                break;
            }
            case PF_SUCCESS: {
                logi("Partial flashing succeeded");
                break;
            }
        }

        disconnectAndClose();

        if (isV1 && action.equals(BROADCAST_PF_ATTEMPT_DFU)) {
            // Try to ensure the NordicDfu profile
            for (int i = 0; i < 5; i++) {
                mBluetoothGatt = connect(deviceAddress);
                if (mBluetoothGatt != null)
                    break;
                lockWait(1000);
            }

            if (mBluetoothGatt != null) {
                refreshV1ForNordicDfu();
                disconnectAndClose();
            }
        }

        if (!action.isEmpty()) {
            logi("Send Intent: " + action);
            final Intent broadcast = new Intent(action);
            if (action.equals(BROADCAST_ERROR)) {
                broadcast.putExtra(EXTRA_DATA, extra);
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        }
        logi("partialFlash End");
    }

    private boolean stateIsError() {
        if (mConnectionState == STATE_READY) {
            return false;
        }
        logi("stateIsError");
        final Intent broadcast = new Intent(BROADCAST_ERROR);
        if (mConnectionState == STATE_BONDED) {
            broadcast.putExtra(EXTRA_DATA, ERROR_BONDED);
        } else {
            broadcast.putExtra(EXTRA_DATA, ERROR_BROKEN);
        }
        disconnectAndClose();
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        return true;
    }

    @SuppressLint("MissingPermission")
    private void disconnectAndClose() {
        working = false;
        if (mBluetoothGatt != null) {
            logi("disconnect");
            mBluetoothGatt.disconnect();
            lockWait(2000);
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }

    private boolean checkAbort() {
        if (!abortReceived) {
            return false;
        }
        disconnectAndClose();
        logi("Send Intent: " + BROADCAST_PF_ABORTED);
        final Intent broadcast = new Intent(BROADCAST_PF_ABORTED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        return true;
    }

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                            int newState) {
            if ( !working) {
                logi("Not working");
                return;
            }
            
            if ( gatt.getDevice().getBondState() != BluetoothDevice.BOND_BONDED) {
                wasNotBonded = true;
            }
            
            logi( "onConnectionStateChange " + newState + " status " + status);

            if ( status != BluetoothGatt.GATT_SUCCESS) {
                if ( mConnectionState == STATE_BONDED_DISCONNECT) {
                    Log.i(TAG, "Disconnect error after bonding");
                    lockWait(300);
                    mConnectionState = STATE_BONDED;
                } else if ( mWaitingForBonding) {
                    if ( gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "Disconnect while waiting for bonding");
                        lockWait(300);
                        mConnectionState = STATE_BONDED;
                    } else {
                        logi("ERROR while connecting - Prepare for retry after a short delay");
                        mConnectionState = STATE_RETRY;
                    }
                } else if ( mConnectionState == STATE_CONNECTING) {
                    logi("ERROR while connecting - Prepare for retry after a short delay");
                    mConnectionState = STATE_RETRY;
                } else {
                    logi("ERROR after connected - fail");
                    mConnectionState = STATE_ERROR;
                }
                // Clear locks
                synchronized (lock) {
                    lock.notifyAll();
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                logi( "STATE_CONNECTED");

                mConnectionState = STATE_CONNECTED;
                
                switch ( gatt.getDevice().getBondState()) {
                    case BluetoothDevice.BOND_BONDED:
                        /* Taken from Nordic. See reasoning here: https://github.com/NordicSemiconductor/Android-DFU-Library/blob/e0ab213a369982ae9cf452b55783ba0bdc5a7916/dfu/src/main/java/no/nordicsemi/android/dfu/DfuBaseService.java#L888 */
                        // NOTE: This also works with shorter waiting time. The gatt.discoverServices() must be called after the indication is received which is
                        // about 600ms after establishing connection. Values 600 - 1600ms should be OK.
                        logi( "Already bonded - Wait for service changed");
                        lockWait(1600);
                        logi( "Already bonded - timeout");
                        callDiscoverServices( gatt);
                        break;
                    case BluetoothDevice.BOND_BONDING:
                        logi( "BOND_BONDING");
                        mWaitingForBonding = true;
                        break;
                    case BluetoothDevice.BOND_NONE:
                        logi( "BOND_NONE");
                        callDiscoverServices( gatt);
                        break;
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if ( mConnectionState == STATE_BONDED_DISCONNECT) {
                    Log.i(TAG, "Disconnect error after bonding");
                    lockWait(300);
                    mConnectionState = STATE_BONDED;
                } else {
                    Log.i(TAG, "Disconnected from GATT server.");
                    mConnectionState = STATE_DISCONNECTED;
                }
            }

            // Clear any locks
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        // New services discovered
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            logi("onServicesDiscovered status " + status);
            if ( !working) {
                return;
            }
            
            if ( status != BluetoothGatt.GATT_SUCCESS) {
                logi("ERROR - status");
                mConnectionState = STATE_ERROR;
                // Clear locks
                synchronized (lock) {
                    lock.notifyAll();
                }
                return;
            }

            if ( mWaitingForServices) {
                if (gatt.getService(UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")) != null) {
                    logi("Hardware Type: V2");
                    hardwareType = MICROBIT_V2;
                } else {
                    logi("Hardware Type: V1");
                    hardwareType = MICROBIT_V1;
                }
                mWaitingForServices = false;

                mConnectionState = STATE_DISCOVERED;

                switch ( gatt.getDevice().getBondState()) {
                    case BluetoothDevice.BOND_BONDED:
                        logi( "Already bonded - READY");
                        mConnectionState = STATE_READY;
                        break;
                    case BluetoothDevice.BOND_BONDING:
                        logi( "BOND_BONDING");
                        mWaitingForBonding = true;
                        break;
                    case BluetoothDevice.BOND_NONE:
                        logi( "BOND_NONE");
                        //TODO: access characteristic to initiate bonding
                        logi("Call createBond()");
                        mWaitingForBonding = true;
                        boolean started = gatt.getDevice().createBond();
                        if (!started) {
                            mWaitingForBonding = false;
                            logi("createBond() failed");
                            mConnectionState = STATE_ERROR;
                        }
                        break;
                }

                // Clear locks
                synchronized (lock) {
                    lock.notifyAll();
                }
                logi("onServicesDiscovered :: Cleared locks");
            }
        }
        @Override
        // API 31 Android 12
        public void onServiceChanged (BluetoothGatt gatt) {
            super.onServiceChanged( gatt);
            logi( "onServiceChanged");
        }
        @Override
        // Result of a characteristic read operation
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            logi( "onServiceChanged");
            logi( String.valueOf(status));
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status){
            logi( "onCharacteristicWrite status " + status + " " + characteristic.getUuid());
            onWriteCharacteristicStatus = status;
            if(status == BluetoothGatt.GATT_SUCCESS) {
                // Success
                logi( "GATT status: Success");
            } else {
                // TODO Attempt to resend?
                logi( "GATT WRITE ERROR. status:" + Integer.toString(status));
            }
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            logi( "onCharacteristicChanged " + characteristic.getUuid());
            
            byte notificationValue[] = characteristic.getValue();
            logi( "Received Notification: " + bytesToHex(notificationValue));

            // What command
            switch(notificationValue[0])
            {
                case REGION_INFO_COMMAND: {
                    // Get Hash + Start / End addresses
                    logi( "Region: " + notificationValue[1]);

                    byte[] startAddress = Arrays.copyOfRange(notificationValue, 2, 6);
                    byte[] endAddress = Arrays.copyOfRange(notificationValue, 6, 10);
                    logi( "startAddress: " + bytesToHex(startAddress) + " endAddress: " + bytesToHex(endAddress));

                    if ( notificationValue[1] == REGION_MAKECODE) {
                        code_startAddress = Byte.toUnsignedLong(notificationValue[5])
                                + Byte.toUnsignedLong(notificationValue[4]) * 256
                                + Byte.toUnsignedLong(notificationValue[3]) * 256 * 256
                                + Byte.toUnsignedLong(notificationValue[2]) * 256 * 256 * 256;

                        code_endAddress = Byte.toUnsignedLong(notificationValue[9])
                                + Byte.toUnsignedLong(notificationValue[8]) * 256
                                + Byte.toUnsignedLong(notificationValue[7]) * 256 * 256
                                + Byte.toUnsignedLong(notificationValue[6]) * 256 * 256 * 256;
                    }

                    byte[] hash = Arrays.copyOfRange(notificationValue, 10, 18);
                    logi( "Hash: " + bytesToHex(hash));

                    // If Region is DAL get HASH
                    if (notificationValue[1] == REGION_DAL && python == false)
                        dalHash = bytesToHex(hash);

                    if (notificationValue[1] == REGION_DAL && python == true)
                        dalHash = bytesToHex(hash);

                    synchronized (region_lock) {
                        region_lock.notifyAll();
                    }

                    break;
                }
                case FLASH_COMMAND: {
                    packetState = notificationValue[1];
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            }
        }

        @Override
        public void onDescriptorRead (BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor,
                                      int status,
                                      byte[] value) {
            logi( "onDescriptorRead :: " + status);

            if(status == BluetoothGatt.GATT_SUCCESS) {
                logi( "Descriptor read success");
                logi( "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                descriptorReadSuccess = true;
                descriptorRead = descriptor;
                descriptorValue = value;
            } else {
                logi( "onDescriptorRead: " + status);
            }

            synchronized (lock) {
                lock.notifyAll();
                logi( "onDescriptorWrite :: clear locks");
            }

        }

        @Override
        public void onDescriptorWrite (BluetoothGatt gatt,
                                       BluetoothGattDescriptor descriptor,
                                       int status){
            logi( "onDescriptorWrite :: " + status);

            if(status == BluetoothGatt.GATT_SUCCESS) {
                logi( "Descriptor success");
                logi( "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                descriptorWriteSuccess = true;
            } else {
                logi( "onDescriptorWrite: " + status);
            }

            synchronized (lock) {
                lock.notifyAll();
                logi( "onDescriptorWrite :: clear locks");
            }
        }
    };

    @SuppressLint("MissingPermission")
    private boolean callDiscoverServices( BluetoothGatt gatt) {
        logi( "callDiscoverServices");
        mWaitingForServices = true;
        final boolean success = gatt.discoverServices();
        if (!success) {
            Log.e(TAG,"ERROR_SERVICE_DISCOVERY_NOT_STARTED");
            mConnectionState = STATE_ERROR;
        }
        return success;
    }

    /**
     * Wait for up to 15ms for onWriteCharacteristic to be called
     *
     * onWriteCharacteristic is called even for NO_RESPONSE
     *
     * In a command and response sequence
     * onWriteCharacteristic may be called before or after onCharacteristicChanged
     *
     * Calling writeCharacteristic again before onWriteCharacteristic
     * returns ERROR_GATT_WRITE_REQUEST_BUSY
     *
     * @return status
     */
    private int waitForOnWriteCharacteristic() throws InterruptedException {
        for ( int i = 0; i < 5; i++) {
            if ( onWriteCharacteristicStatus != BLE_PENDING) {
                break;
            }
            logi( "waitForOnWriteCharacteristic #" + i);
            Thread.sleep(3);
        }
        logi( "waitForOnWriteCharacteristic = " + onWriteCharacteristicStatus);
        return onWriteCharacteristicStatus;
    }


    @SuppressLint("MissingPermission")
    private int writeCharacteristic( BluetoothGattCharacteristic c, byte[] data, int writeType) {
        logi( "writeCharacteristic " + c.getUuid() + " writeType " + writeType);
        onWriteCharacteristicStatus = BLE_PENDING;
        if ( Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            c.setWriteType( writeType);
            c.setValue(data);
            int status = mBluetoothGatt.writeCharacteristic(c) ? BluetoothGatt.GATT_SUCCESS : BLE_ERROR_UNKNOWN;
            logi( "writeCharacteristic status " + status);
            return status;
        }

        int status = mBluetoothGatt.writeCharacteristic( c, data, writeType);
        logi( "writeCharacteristic status " + status);
        return status;
    }

    // Write to BLE Flash Characteristic
    private int writeCharacteristicPF( byte[] data, int writeType) {
        return writeCharacteristic( partialFlashCharacteristic, data, writeType);
    }

    private int attemptPartialFlash(String filePath) {
        logi( "Flashing: " + filePath);

        sendProgressBroadcastStart();

        try {

            logi( "attemptPartialFlash()");
            logi( filePath);
            HexUtils hex = new HexUtils(filePath);
            logi( "searchForData()");
            python = false;
            HexPos dataPos = findMakeCodeData( hex);
            if ( dataPos == null) {
                dataPos = findPythonData( hex);
                if ( dataPos != null) {
                    python = true;
                }
            }

            if ( dataPos == null) {
                logi( "No partial flash data");
                return PF_ATTEMPT_DFU;
            }

            logi( "Found partial flash data at " + dataPos.line + " at offset " + dataPos.part);

            // Get Memory Map from Microbit
            code_startAddress = code_endAddress = 0;
            if ( !readMemoryMap())
            {
                Log.w(TAG, "Failed to read memory map");
                return PF_ATTEMPT_DFU;
            }

            if ( code_startAddress == 0 || code_endAddress <= code_startAddress)
            {
                logi( "Failed to read memory map code address");
                return PF_ATTEMPT_DFU;
            }

            // Compare DAL hash
            if ( !fileHash.equals( dalHash)) {
                logi( "Hash " + fileHash + " != " + ( dalHash));
                return PF_ATTEMPT_DFU;
            }

            int count = 0;
            int numOfLines = hex.numOfLines() - dataPos.line;
            logi( "Total lines: " + numOfLines);

            int packetNum = 0;
            int lineCount = 0;
            int part = dataPos.part;
            int line0 = lineCount;
            int part0 = part;

            int  addrLo = hex.getRecordAddressFromIndex( dataPos.line + lineCount);
            int  addrHi = hex.getSegmentAddress(dataPos.line + lineCount);
            long addr   = (long) addrLo + (long) addrHi * 256 * 256;

            String hexData;
            String partData;

            Log.w(TAG, "Code start " + code_startAddress + " end " + code_endAddress);
            Log.w(TAG, "First line " + addr);

            // Ready to flash!
            // Loop through data
            logi( "enter flashing loop");

            long addr0 = addr + part / 2;  // two hex digits per byte
            int  addr0Lo = (int) ( addr0 % (256 * 256));
            int  addr0Hi = (int) ( addr0 / (256 * 256));

            if ( code_startAddress != addr0) {
                logi( "Code start address doesn't match");
                return PF_ATTEMPT_DFU;
            }

            // TODO - check size of code in file matches micro:bit

            boolean endOfFile = false;
            long startTime = SystemClock.elapsedRealtime();
            while (true) {
                // Timeout if total is > 30 seconds
                if(SystemClock.elapsedRealtime() - startTime > 60000) {
                    logi( "Partial flashing has timed out");
                    return PF_FAILED;
                }

                // Check if EOF
                if ( endOfFile || hex.getRecordTypeFromIndex( dataPos.line + lineCount) != 0) {
                    if ( count == 0) {
                        break;
                    }
                    endOfFile = true;
                }

                if ( endOfFile) {
                    // complete the batch of 4 packets with FF
                    char[] c32 = new char[32];
                    Arrays.fill( c32, 'F');
                    hexData = new String( c32);
                    partData = hexData;
                } else {
                    addrLo = hex.getRecordAddressFromIndex(dataPos.line + lineCount);
                    addrHi = hex.getSegmentAddress(dataPos.line + lineCount);
                    addr = (long) addrLo + (long) addrHi * 256 * 256;

                    hexData = hex.getDataFromIndex(dataPos.line + lineCount);
                    if (part + 32 > hexData.length()) {
                        partData = hexData.substring(part);
                    } else {
                        partData = hexData.substring(part, part + 32);
                    }
                }

                int offsetToSend = 0;
                if ( count == 0)
                {
                    line0 = lineCount;
                    part0 = part;
                    addr0 = addr + part / 2;  // two hex digits per byte
                    addr0Lo = (int) ( addr0 % (256 * 256));
                    addr0Hi = (int) ( addr0 / (256 * 256));
                    offsetToSend = addr0Lo;
                } else if (count == 1) {
                    offsetToSend = addr0Hi;
                }

                logi( packetNum + " " + count + " addr0 " + addr0 + " offsetToSend " + offsetToSend + " line " + lineCount + " addr " + addr + " part " + part + " data " + partData + " endOfFile " + endOfFile);

                // recordToByteArray() builds a PF command block with the data
                byte[] chunk = HexUtils.recordToByteArray(partData, offsetToSend, packetNum);

                // Write without response
                // Wait for previous write to complete
                int writeStatus = writeCharacteristicPF( chunk, NO_RESPONSE);

                // Sleep after 4 packets
                count++;
                if ( count != 4) {
                    waitForOnWriteCharacteristic();
                } else {
                    count = 0;

                    // Wait for notification
                    logi( "Wait for notification");

                    // Send broadcast while waiting
                    int percent = Math.round((float)100 * ((float)(lineCount) / (float)(numOfLines)));
                    sendProgressBroadcast(percent);

                    long timeout = SystemClock.elapsedRealtime();
                    while(packetState == PACKET_STATE_WAITING) {
                        synchronized (lock) {
                            lock.wait(5000);
                        }

                        // Timeout if longer than 5 seconds
                        if((SystemClock.elapsedRealtime() - timeout) > 5000)
                            return PF_FAILED;
                    }

                    packetState = PACKET_STATE_WAITING;

                    logi( "/Wait for notification");
                }

                // If notification is retransmit -> retransmit last block.
                // Else set start of new block
                if(packetState == PACKET_STATE_RETRANSMIT) {
                    lineCount = line0;
                    part = part0;
                    endOfFile = false;
                } else {
                    if ( !endOfFile) {
                        // Next part
                        part = part + partData.length();
                        if (part >= hexData.length()) {
                            part = 0;
                            lineCount = lineCount + 1;
                        }
                    }
                }

                // Always increment packet #
                packetNum = packetNum + 1;
            }

            Thread.sleep(100); // allow time for write to complete

            // Write End of Flash packet
            byte[] endOfFlashPacket = {(byte)0x02};
            int writeStatus = writeCharacteristicPF( endOfFlashPacket, NO_RESPONSE);

            Thread.sleep(100); // allow time for write to complete

            // Finished Writing
            logi( "Flash Complete");
            packetState = PACKET_STATE_COMPLETE_FLASH;
            sendProgressBroadcast(100);
            sendProgressBroadcastComplete();

            // Time execution
            long endTime = SystemClock.elapsedRealtime();
            long elapsedMilliSeconds = endTime - startTime;
            double elapsedSeconds = elapsedMilliSeconds / 1000.0;
            logi( "Flash Time: " + Float.toString((float)elapsedSeconds) + " seconds");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Check complete before leaving intent
        if (packetState != PACKET_STATE_COMPLETE_FLASH) {
            return PF_FAILED;
        }

        return PF_SUCCESS;
    }

    private class HexPos {
        public int line;
        public int part; // offset into line data in characters
        public int sizeBytes;
        public void hexPos() {
            line = -1;
            part = -1;
            sizeBytes = 0;
        }
    }

    private HexPos findMakeCodeData( HexUtils hex) throws IOException {
        HexPos pos = new HexPos();
        pos.line = hex.searchForData(PXT_MAGIC);
        if ( pos.line < 0) {
            return null;
        }
        String magicData = hex.getDataFromIndex( pos.line);
        pos.part = magicData.indexOf(PXT_MAGIC);
        long hdrAddress = hexPosToAddress( hex, pos);
        long hashAddress = hdrAddress + PXT_MAGIC.length() / 2;
        HexPos hashPos = hexAddressToPos( hex, hashAddress);
        if ( hashPos == null) {
            return null;
        }
        hashPos.sizeBytes =  8;
        fileHash = hexGetData( hex, hashPos);
        if ( fileHash.length() < 8 * 2) {  // 16 bytes
            return null;
        }
        // TODO - find end of data pos.sizeBytes
        return pos;
    }

    //    Micropython region table
    //    https://github.com/microbit-foundation/micropython-microbit-v2/blob/a76e1413bcd66f128a31d98756fc3d1f336d1580/src/addlayouttable.py

    public final static int PYTHON_HEADER_SIZE = 16;
    public final static int PYTHON_REGION_SIZE = 16;

    private HexPos findPythonData( HexUtils hex) throws IOException {
        HexPos pos = new HexPos();
        pos.line = hex.searchForDataRegEx(UPY_MAGIC);
        if ( pos.line < 0) {
            return null;
        }
        String header = hex.getDataFromIndex( pos.line);
        pos.part = header.indexOf(UPY_MAGIC1);
        pos.sizeBytes = PYTHON_HEADER_SIZE;
        header = hexGetData( hex, pos);
        if ( header.length() < PYTHON_HEADER_SIZE * 2) {
            return null;
        }
        int version     = hexToUint16( header, 8);
        int table_len   = hexToUint16( header, 12);
        int num_reg     = hexToUint16( header, 16);
        int pageLog2    = hexToUint16( header, 20);
        if ( version != 1) {
            return null;
        }
        if ( table_len != num_reg * 16) {
            return null;
        }
        int page = hardwareType == MICROBIT_V1 ? 0x400 : 0x1000;
        if ( 1 << pageLog2 != page) {
            return null;
        }

        long codeStart = -1;
        long codeLength = -1;

        long hdrAddress = hexPosToAddress( hex, pos);
        for ( int regionIndex = 0; regionIndex < num_reg; regionIndex++)
        {
            long regionAddress = hdrAddress - table_len + (long) ( regionIndex * PYTHON_REGION_SIZE);
            pos = hexAddressToPos( hex, regionAddress);
            if ( pos == null) {
                return null;
            }
            pos.sizeBytes =  PYTHON_REGION_SIZE;
            String region = hexGetData( hex, pos);
            if ( region.length() < PYTHON_REGION_SIZE * 2) {
                return null;
            }
            int regionID    = hexToUint8(  region, 0);
            int hashType    = hexToUint8(  region, 2);
            int startPage   = hexToUint16( region, 4);
            long length     = hexToUint32( region, 8);
            long hashPtr    = hexToUint32( region, 16);
            String hash     = region.substring( 16, 32);

            // Extract regionHash
            String regionHash = null;
            switch ( hashType)
            {
                default:
                    // Unknown
                    return null;
                case 0:
                    //hash data is empty
                    break;
                case 1:
                    // hash data contains 8 bytes of verbatim data
                    regionHash = hash;
                    break;
                case 2: {
                    // hash data contains a 4-byte pointer to a string of up tp 100 chars
                    // hash is the crc32 of the string
                    HexPos hashPos = hexAddressToPos( hex, hashPtr);
                    if ( hashPos == null) {
                        return null;
                    }
                    hashPos.sizeBytes = 100;
                    String hashData = hexGetData( hex, hashPos);
                    if ( hashData.isEmpty()) {
                        return null;
                    }
                    int strLen = 0;
                    while ( strLen < hashData.length() / 2) {
                        int chr = hexToUint8( hashData, strLen * 2);
                        if ( chr == 0) {
                            break;
                        }
                        strLen++;
                    }
                    byte [] strBytes = new byte[ strLen];
                    for ( int i = 0; i < strLen; i++) {
                        int chr = hexToUint8( hashData, i * 2);
                        strBytes[i] = (byte) chr;
                    }
                    CRC32 crc32 = new CRC32();
                    crc32.update( strBytes);
                    long crc = crc32.getValue();
                    byte [] hashBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong( crc).array();
                    regionHash = bytesToHex( hashBytes);
                    break;
                }
            }

            // Use regionHash from app region and code start & end from file system
            switch ( regionID)
            {
                case 1: // softdevice
                    break;
                case 2: // micropython app
                    fileHash = regionHash;
                    break;
                case 3: // file system
                    codeStart = (long) startPage * page;
                    codeLength = length;
                    break;
            }
        }

        if ( codeStart < 0 || codeLength < 0) {
            return null;
        }
        int index = hex.searchForAddress( codeStart);
        pos = hexAddressToPos( hex, codeStart);
        if ( pos == null) {
            return null;
        }
        pos.sizeBytes = (int) codeLength;
        return pos;
    }

    private long hexPosToAddress( HexUtils hex, HexPos pos) throws IOException {
        int addrLo = hex.getRecordAddressFromIndex( pos.line);
        int addrHi = hex.getSegmentAddress(pos.line);
        long addr = (long) addrLo + (long) addrHi * 256 * 256;
        return addr + pos.part / 2;
    }

    private HexPos hexAddressToPos( HexUtils hex, long address) throws IOException {
        HexPos pos = new HexPos();
        pos.line = hex.searchForAddress( address);
        if ( pos.line < 0) {
            return null;
        }
        int lineAddr = hex.getRecordAddressFromIndex( pos.line);
        long addressLo = address % 0x10000;
        long offset = addressLo - lineAddr;
        pos.part = (int) offset * 2;
        return pos;
    }

    private String hexGetData( HexUtils hex, final HexPos pos) throws IOException {
        StringBuilder data = new StringBuilder();
        int line = pos.line;
        int part = pos.part;
        int size = pos.sizeBytes * 2; // 2 characters per byte
        while ( size > 0) {
            int type = hex.getRecordTypeFromIndex( line);
            if ( type != 0 && type != 0x0D) {
                line++;
                part = 0;
            } else {
                String lineData = hex.getDataFromIndex(line);
                int len = lineData.length();
                int chunk = Math.min(len - part, size);
                if (chunk > 0) {
                    data.append(lineData.substring(part, part + chunk));
                    part += chunk;
                    size -= chunk;
                }
                if (size > 0 && part >= len) {
                    line += 1;
                    part = 0;
                    if (line >= hex.numOfLines()) {
                        break;
                    }
                }
            }
        }
        return data.toString();
    }

    private static int hexToUint8( String hex, int idx) {
        int hi = Character.digit( hex.charAt( idx), 16);
        int lo = Character.digit( hex.charAt( idx + 1), 16);
        if ( lo < 0 || hi < 0) {
            return -1;
        }
        return hi * 16 + lo;
    }

    private static int hexToUint16( String hex, int idx)
    {
        int lo = hexToUint8( hex, idx);
        int hi = hexToUint8( hex, idx + 2);
        if ( lo < 0 || hi < 0) {
            return -1;
        }
        return hi * 256 + lo;
    }

    private static long hexToUint32( String hex, int idx)
    {
        long b0 = hexToUint8( hex, idx);
        long b1 = hexToUint8( hex, idx + 2);
        long b2 = hexToUint8( hex, idx + 4);
        long b3 = hexToUint8( hex, idx + 6);
        if ( b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            return -1;
        }
        return b0 + b1 * 0x100 + b2 * 0x10000 + b3 * 0x1000000;
    }

    @SuppressLint("MissingPermission")
    protected BluetoothGatt connect(@NonNull final String address) {
        if (!mBluetoothAdapter.isEnabled())
            return null;

        logi( "connect");

        long start = SystemClock.elapsedRealtime();

        working = true;
        mConnectionState = STATE_CONNECTING;
        mWaitingForServices = false;
        mWaitingForBonding = false;
        int stateWas = mConnectionState;

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        BluetoothGatt gatt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gatt = device.connectGatt(
                    this,
                    false,
                    mGattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_2M_MASK);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(
                    this,
                    false,
                    mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(
                    this,
                    false,
                    mGattCallback);
        }

        if ( gatt == null) {
            mConnectionState = STATE_RETRY;
            working = false;
            return null;
        }

        // We have to wait until the device is connected and services are discovered
        // Connection error may occur as well.
        try {
            boolean waiting = true;
            while ( waiting && mConnectionState != STATE_READY) {

                int timeout = 20000;
                if ( mConnectionState == STATE_BONDED_DISCONNECT) {
                    timeout = 6000;
                } else if ( mWaitingForBonding) {
                    timeout = 30000;
                }

                synchronized (lock) {
                    lock.wait( timeout);
                }

                String time = Float.toString((float) ( SystemClock.elapsedRealtime() - start) / 1000.0f);
                switch ( mConnectionState) {
                    case STATE_DISCOVERED:
                        logi( time + ": STATE_DISCOVERED");
                        break;
                    case STATE_READY:
                        logi( time + ": STATE_READY");
                        break;
                    case STATE_ERROR:
                        logi( time + ": STATE_ERROR");
                        break;
                    case STATE_RETRY:
                        logi( time + ": STATE_RETRY");
                        break;
                    case STATE_BONDED:
                        logi( time + ": STATE_BONDED");
                        break;
                    case STATE_BONDED_DISCONNECT:
                        logi( time + ": STATE_BONDED_DISCONNECT");
                        break;
                    case STATE_CONNECTED:
                        logi( time + ": STATE_CONNECTED");
                        break;
                    case STATE_CONNECTING:
                        logi( time + ": STATE_CONNECTING");
                        break;
                    case STATE_DISCONNECTED:
                        logi( time + ": STATE_DISCONNECTED");
                        break;
                    default:
                        logi( time + ": " + mConnectionState);
                        break;
                }

                if ( abortReceived) {
                    mConnectionState = STATE_ERROR;
                }

                waiting = false;
                switch ( mConnectionState) {
                    case STATE_READY:
                    case STATE_DISCONNECTED:
                    case STATE_ERROR:
                    case STATE_RETRY:
                    case STATE_BONDED:
                        break;
                    default:
                        if ( stateWas != mConnectionState)
                            waiting = true;
                        break;
                }
                stateWas = mConnectionState;
            }
        } catch (final InterruptedException e) {
            mConnectionState = STATE_ERROR;
        }

        if ( mConnectionState != STATE_READY) {
            working = false;
            gatt.disconnect();
            lockWait(2000);
            gatt.close();
            return null;
        }

        logi( "Connected to gatt");
        logi( gatt.toString());
        return gatt;
    }

    private boolean lockWait( long timeout)
    {
        logi( "lockWait");
        synchronized (lock) {
            try {
                lock.wait(timeout);
            } catch (final Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    private void lockNotify() {
        // Clear locks
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private boolean connectedHasV1MicroBitDfu() {
        return mBluetoothGatt.getService( MICROBIT_DFU_SERVICE) != null;
    }

    private boolean connectedHasV1NordicDfu() {
        return mBluetoothGatt.getService( NORDIC_DFU_SERVICE) != null;
    }

    private boolean connectedHasV1Dfu() {
        return connectedHasV1MicroBitDfu() || connectedHasV1NordicDfu();
    }

    @SuppressLint("MissingPermission")
    private void refreshV1(boolean wantMicroBitDfu) {
        if (connectedHasV1Dfu()) {
            if (wantMicroBitDfu != connectedHasV1MicroBitDfu()) {
                logi( "refreshV1");
                try {
                    final Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
                    refresh.invoke(mBluetoothGatt);
                } catch (final Exception e) {
                }
                mWaitingForServices = true;
                mBluetoothGatt.discoverServices();
                lockWait(2000);
            }
        }
    }

    private void refreshV1ForMicroBitDfu() {
        refreshV1( true);
    }

    private void refreshV1ForNordicDfu() {
        refreshV1( false);
    }

    @SuppressLint("MissingPermission")
    private BluetoothGattCharacteristic serviceChangedCharacteristic() {
        BluetoothGattService gas = mBluetoothGatt.getService(GENERIC_ATTRIBUTE_SERVICE_UUID);
        if (gas == null) {
            return null;
        }
        BluetoothGattCharacteristic scc = gas.getCharacteristic(SERVICE_CHANGED_UUID);
        if (scc == null) {
            return null;
        }
        return scc;
    }

    @SuppressLint("MissingPermission")
    private boolean cccEnabled(BluetoothGattCharacteristic chr, boolean notify) {
        logi( "cccEnabled");
        BluetoothGattDescriptor ccc = chr.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (ccc == null) {
            return false;
        }

        descriptorReadSuccess = false;
        mBluetoothGatt.readDescriptor(ccc);
        lockWait(1000);
        if (!descriptorReadSuccess
                || !descriptorRead.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIG)
                || !descriptorRead.getCharacteristic().getUuid().equals(chr.getUuid())) {
            return false;
        }
        if ( descriptorValue == null || descriptorValue.length != 2) {
            return false;
        }

        boolean enabled = false;
        if ( notify) {
            enabled = descriptorValue[0] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0]
                    && descriptorValue[1] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[1];
        } else {
            enabled = descriptorValue[0] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[0]
                    && descriptorValue[1] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[1];
        }
        return enabled;
    }

    @SuppressLint("MissingPermission")
    private boolean cccEnable( BluetoothGattCharacteristic chr, boolean notify) {
        logi( "cccEnable");
        BluetoothGattDescriptor ccc = chr.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (ccc == null) {
            return false;
        }

        mBluetoothGatt.setCharacteristicNotification( chr, true);

        byte [] enable = notify
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

        descriptorWriteSuccess = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mBluetoothGatt.writeDescriptor( ccc, enable);
        } else {
            ccc.setValue(enable);
            mBluetoothGatt.writeDescriptor( ccc);
        }
        lockWait(1000);
        return descriptorWriteSuccess;
    }

    protected boolean partialFlashCharacteristicCheck() {
        logi( "partialFlashCharacteristicCheck");

        // Check for partial flashing
        pfService = mBluetoothGatt.getService(PARTIAL_FLASHING_SERVICE);

        // Check partial flashing service exists
        if (pfService == null) {
            logi( "Partial Flashing Service == null");
            return false;
        }

        // Check for characteristic
        partialFlashCharacteristic = pfService.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC);

        if (partialFlashCharacteristic == null) {
            logi( "Partial Flashing Characteristic == null");
            return false;
        }

        logi( "Enable notifications");
        if ( !cccEnable( partialFlashCharacteristic, true))
        {
            logi( "Enable notifications failed");
            return false;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    protected boolean enterDFUModeV1() {
        logi( "enterDFUModeV1");

        BluetoothGattService dfuService = mBluetoothGatt.getService(MICROBIT_DFU_SERVICE);
        // Write Characteristic to enter DFU mode
        if (dfuService == null) {
            logi( "DFU Service is null");
            return false;
        }
        BluetoothGattCharacteristic microbitDFUCharacteristic = dfuService.getCharacteristic(MICROBIT_DFU_CHARACTERISTIC);
        if (microbitDFUCharacteristic == null) {
            logi( "DFU Characteristic is null");
            return false;
        }

        byte payload[] = {0x01};
        microbitDFUCharacteristic.setValue(payload);
        int status = writeCharacteristic( microbitDFUCharacteristic, payload, WITH_RESPONSE);
        logi( "MicroBitDFU :: Enter DFU Result " + status);

        synchronized (lock) {
            try {
                lock.wait(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // assume it succeeded
        return true;
    }

    /*
    Read Memory Map from the MB
     */
    @SuppressLint("MissingPermission")
    private boolean readMemoryMap() {
        logi( "readMemoryMap");

        try {
            for (int i = 0; i < 3; i++)
            {
                // Get Start, End, and Hash of each Region
                // Request Region
                logi( "Request Region " + i);
                byte[] payload = {REGION_INFO_COMMAND, (byte)i};
                if(partialFlashCharacteristic == null || mBluetoothGatt == null) return false;
                int status = writeCharacteristicPF( payload, WITH_RESPONSE);
                if( status != BluetoothGatt.GATT_SUCCESS) {
                    logi( "Failed to write to Region characteristic " + i);
                    return false;
                }
                synchronized (region_lock) {
                    region_lock.wait(2000);
                }
                if ( waitForOnWriteCharacteristic() != BluetoothGatt.GATT_SUCCESS) {
                    return false;
                }
            }
        } catch (Exception e){
            Log.e(TAG, e.toString());
            return false;
        }

        return true;
    }

    public static String bytesToHex(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}

