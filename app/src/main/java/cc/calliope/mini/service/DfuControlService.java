package cc.calliope.mini.service;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.UUID;

import androidx.annotation.IntDef;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import cc.calliope.mini.App;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.Version;

import static android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothDevice.ERROR;
import static android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE;
import static android.bluetooth.BluetoothGatt.GATT_SUCCESS;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;


public class DfuControlService extends Service {
    private static final String TAG = "DfuControlService";
    private static final UUID DEVICE_FIRMWARE_UPDATE_CONTROL_SERVICE_UUID = UUID.fromString("E95D93B0-251D-470A-A062-FA1922DFA9A8");
    private static final UUID DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID = UUID.fromString("E95D93B1-251D-470A-A062-FA1922DFA9A8");
    private static final UUID SECURE_DEVICE_FIRMWARE_UPDATE_SERVICE_UUID = UUID.fromString("0000FE59-0000-1000-8000-00805F9B34FB");
    private static final int DELAY_TO_CLEAR_CACHE = 2000;
    public static final String BROADCAST_START = "cc.calliope.mini.DFUControlService.BROADCAST_START";
    public static final String BROADCAST_COMPLETED = "cc.calliope.mini.DFUControlService.BROADCAST_COMPLETE";
    public static final String BROADCAST_FAILED = "cc.calliope.mini.DFUControlService.BROADCAST_FAILED";
    public static final String BROADCAST_ERROR = "cc.calliope.mini.DFUControlService.BROADCAST_ERROR";
    public static final String EXTRA_BOARD_VERSION = "cc.calliope.mini.DFUControlService.EXTRA_BOARD_VERSION";
    public static final String EXTRA_ERROR_CODE = "cc.calliope.mini.DFUControlService.EXTRA_ERROR_CODE";
    public static final String EXTRA_ERROR_MESSAGE = "cc.calliope.mini.DFUControlService.EXTRA_ERROR_MESSAGE";
    public static final String EXTRA_DEVICE_ADDRESS = "cc.calliope.mini.DFUControlService.EXTRA_DEVICE_ADDRESS";
    public static final String EXTRA_MAX_RETRIES_NUMBER = "cc.calliope.mini.DFUControlService.EXTRA_MAX_RETRIES_NUMBER";
    public static final int GATT_DISCONNECTED_BY_DEVICE = 19;
    private final Object mLock = new Object();
    private int maxRetries;
    private int numOfRetries = 0;
    private boolean isComplete = false;
    private int bondState;
    private String deviceAddress;
    public static final int UNIDENTIFIED = 0;
    /**
     * Version 1.x, 2.0, 2,1
     * https://calliope-mini.github.io/v10/
     * https://calliope-mini.github.io/v20/
     * https://calliope-mini.github.io/v21/
     */
    public static final int MINI_V1 = 1;
    /**
     * New version
     */
    public static final int MINI_V2 = 2;

    @IntDef({UNIDENTIFIED, MINI_V1, MINI_V2})
    @Retention(RetentionPolicy.SOURCE)
    public @interface HardwareVersion {
    }

    private int boardVersion = UNIDENTIFIED;
    private App app;

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(deviceAddress)) {
                return;
            }

            final String action = intent.getAction();
            // Check if action is valid
            if (action == null) {
                return;
            }

            // Take action depending on new bond state
            if (action.equals(ACTION_BOND_STATE_CHANGED)) {
                bondState = intent.getIntExtra(EXTRA_BOND_STATE, ERROR);
                switch (bondState) {
                    case BOND_BONDING -> Utils.log(Log.WARN, TAG, "Bonding started");
                    case BOND_BONDED -> {
                        Utils.log(Log.WARN, TAG, "Bonding succeeded");
                        synchronized (mLock) {
                            mLock.notifyAll();
                        }
                    }
                    case BOND_NONE -> {
                        Utils.log(Log.WARN, TAG, "Oh oh");
                        synchronized (mLock) {
                            mLock.notifyAll();
                        }
                    }
                }
            }
        }
    };

    @SuppressWarnings({"MissingPermission"})
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            Utils.log(Log.ASSERT, TAG, "onConnectionStateChange(gatt: " + gatt + ", status: " + status + ", newState: " + newState + ")");

            if (status == GATT_SUCCESS || status == GATT_DISCONNECTED_BY_DEVICE) {
                if (newState == STATE_CONNECTED) {
                    if (bondState == BOND_NONE || bondState == BOND_BONDED) {
                        if (bondState == BOND_BONDED && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                            waitFor(1600);
                        }
                        boolean result = gatt.discoverServices();
                        if (!result) {
                            Utils.log(Log.ERROR, TAG, "discoverServices failed to start");
                        }
                    } else if (bondState == BOND_BONDING) {
                        Utils.log(Log.WARN, TAG, "waiting for bonding to complete");
                    }
                } else if (newState == STATE_DISCONNECTED) {
                    stopService(gatt);
                }
            } else {
                String message = getStringFromResource(GattStatus.get(status).getMessage());
                gatt.disconnect();
                sendError(status, message);
                //stopService(gatt);
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            Utils.log(Log.ASSERT, TAG, "onServicesDiscovered(status: " + status + ")");

            if (status == GATT_SUCCESS) {
                startLegacyDfu(gatt);
            } else {
                gatt.disconnect();
                sendError(status, "Services discovered not success");
            }
        }

        // Other methods just pass the parameters through
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Utils.log(Log.ASSERT, TAG, "onCharacteristicWrite(status: " + status + ")");

            if (bondState == BOND_NONE || bondState == BOND_BONDED) {
                if (status == GATT_SUCCESS) {
                    isComplete = true;
                    boardVersion = MINI_V1;
                } else {
                    gatt.disconnect();
                    sendError(status, "Characteristic write not success");
                }
            } else if (bondState == BOND_BONDING) {
                Utils.log(Log.WARN, TAG, "waiting for bonding to complete");
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Utils.log(Log.ASSERT, TAG, "onCharacteristicRead(gatt: " + gatt + ", characteristic: " + characteristic + ", status: " + status + ")");

            if (bondState == BOND_NONE || bondState == BOND_BONDED) {
                if (status == GATT_SUCCESS) {
                    characteristic.setValue(1, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    try {
                        Utils.log(Log.DEBUG, TAG, "Writing Flash Command...");
                        gatt.writeCharacteristic(characteristic);
                    } catch (Exception e) {
                        e.printStackTrace();
                        gatt.disconnect();
                        sendError(133, e.toString());
                    }
                }
            } else if (bondState == BOND_BONDING) {
                Utils.log(Log.WARN, TAG, "waiting for bonding to complete");
            }
        }
    };


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Utils.log(Log.DEBUG, TAG, "Сервіс запущений.");

        registerReceiver(bondStateReceiver, new IntentFilter(ACTION_BOND_STATE_CHANGED));

        deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        maxRetries = intent.getIntExtra(EXTRA_MAX_RETRIES_NUMBER, 2);

        app = (App) getApplication();
        app.setAppState(App.APP_STATE_CONNECTING);

        connect();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(bondStateReceiver);

        final Intent broadcast = new Intent(isComplete ? BROADCAST_COMPLETED : BROADCAST_FAILED);
        broadcast.putExtra(EXTRA_BOARD_VERSION, boardVersion);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(broadcast);

        app.setAppState(App.APP_STATE_STANDBY);
        Utils.log(Log.DEBUG, TAG, "Сервіс зупинений.");
    }

    private void connect() {
        if ((Version.VERSION_S_AND_NEWER && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            Utils.log(Log.ERROR, TAG, "BLUETOOTH permission no granted");
            return;
        }
        Utils.log(Log.DEBUG, TAG, "Connecting to the device...");

        final Intent broadcast = new Intent(BROADCAST_START);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(broadcast);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if(!adapter.isEnabled()){
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
        bondState = device.getBondState();

        if (Version.VERSION_O_AND_NEWER) {
            Utils.log(Log.DEBUG, TAG, "gatt = device.connectGatt(autoConnect = false, TRANSPORT_LE, preferredPhy = LE_1M | LE_2M)");
            device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_2M_MASK);
        } else if (Version.VERSION_M_AND_NEWER) {
            Utils.log(Log.DEBUG, TAG, "gatt = device.connectGatt(autoConnect = false, TRANSPORT_LE)");
            device.connectGatt(this, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            Utils.log(Log.DEBUG, TAG, "gatt = device.connectGatt(autoConnect = false)");
            device.connectGatt(this, false, gattCallback);
        }

    }

    @SuppressWarnings({"MissingPermission"})
    private void stopService(BluetoothGatt gatt) {
        if (bondState == BOND_BONDING) {
            waitUntilBonded();
        }
        clearServicesCache(gatt);
        gatt.close();
        stopSelf();
    }

    @SuppressWarnings({"MissingPermission"})
    private void startLegacyDfu(BluetoothGatt gatt) {
        BluetoothGattService legacyDfuService = gatt.getService(DEVICE_FIRMWARE_UPDATE_CONTROL_SERVICE_UUID);
        if (legacyDfuService == null) {
            startSecureDfu(gatt);
            return;
        }

        final BluetoothGattCharacteristic legacyDfuCharacteristic = legacyDfuService.getCharacteristic(DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID);
        if (legacyDfuCharacteristic == null) {
            gatt.disconnect();
            sendError(133, "Cannot find DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID");
            return;
        }

        boolean res = gatt.readCharacteristic(legacyDfuCharacteristic);
        Utils.log(Log.WARN, TAG, "readCharacteristic: " + res);
    }

    @SuppressWarnings({"MissingPermission"})
    private void startSecureDfu(BluetoothGatt gatt) {
        BluetoothGattService secureDfuService = gatt.getService(SECURE_DEVICE_FIRMWARE_UPDATE_SERVICE_UUID);
        if (secureDfuService == null) {
            if (numOfRetries < maxRetries) {
                Utils.log(Log.WARN, TAG, "Retrying to discover services...");
                numOfRetries++;
                clearServicesCache(gatt);
                gatt.discoverServices();
            } else {
                gatt.disconnect();
                sendError(133, "Cannot find services");
            }
        } else {
            isComplete = true;
            boardVersion = MINI_V2;
            gatt.disconnect();
        }
    }

    protected void clearServicesCache(BluetoothGatt gatt) {
        try {
            //noinspection JavaReflectionMemberAccess
            final Method refresh = gatt.getClass().getMethod("refresh");
            //noinspection ConstantConditions
            final boolean success = (boolean) refresh.invoke(gatt);
            Utils.log(Log.DEBUG, TAG, "Refreshing result: " + success);
        } catch (final Exception e) {
            Utils.log(Log.ERROR, TAG, "An exception occurred while refreshing device. " + e);
        }
        waitFor(DELAY_TO_CLEAR_CACHE);
    }

    protected void waitUntilBonded() {
        try {
            synchronized (mLock) {
                while (bondState == BOND_BONDING)
                    mLock.wait();
            }
        } catch (final InterruptedException e) {
            Utils.log(Log.ERROR, TAG, "Sleeping interrupted, " + e);
        }
    }

    protected void waitFor(final long millis) {
        synchronized (mLock) {
            try {
                Utils.log(Log.DEBUG, TAG, "Wait for " + millis + " millis");
                mLock.wait(millis);
            } catch (final InterruptedException e) {
                Utils.log(Log.ERROR, TAG, "Sleeping interrupted, " + e);
            }
        }
    }

    private void sendError(final int code, final String message) {
        final Intent broadcast = new Intent(BROADCAST_ERROR);
        broadcast.putExtra(EXTRA_ERROR_CODE, code);
        broadcast.putExtra(EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(broadcast);
        Utils.log(Log.ERROR, TAG, message);
    }

    private String getStringFromResource(int resId) {
        try {
            return getResources().getString(resId);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}