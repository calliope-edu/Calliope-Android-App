package cc.calliope.mini.pf;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import static android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED;
import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothDevice.BOND_BONDING;
import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.bluetooth.BluetoothDevice.ERROR;
import static android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE;

public abstract class MyPartialFlashingBaseService extends Service {
    private static final String TAG = "PartialFlashingService";
    private static final int DELAY_TO_CLEAR_CACHE = 2000;
    public static final String EXTRA_DEVICE_ADDRESS = "org.microbit.android.partialflashing.EXTRA_DEVICE_ADDRESS";
    public static final String EXTRA_FILE_PATH = "org.microbit.android.partialflashing.EXTRA_FILE_PATH";
    public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";
    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
    public static final UUID PARTIAL_FLASHING_SERVICE_UUID = UUID.fromString("E97DD91D-251D-470A-A062-FA1922DFA9A8");
    public static final UUID PARTIAL_FLASHING_CHARACTERISTIC_UUID = UUID.fromString("E97D3B10-251D-470A-A062-FA1922DFA9A8");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";
    private static final String UPY_MAGIC = ".*FE307F59.{16}9DD7B1C1.*";
    private static final byte REGION_INFO_COMMAND = (byte) 0x00;
    private static final byte FLASH_COMMAND = (byte) 0x01;
    private static final byte STATUS = (byte) 0xEE;
    private static final int REGION_DAL = 1;
    private static final byte MODE_APPLICATION = 0x01;

    private final Object lock = new Object();
    private String deviceAddress;
    private String filePath;
    private BluetoothGattCharacteristic partialFlashingCharacteristic;
    private String dalHash;
    private int bondState;
    private ServiceHandler serviceHandler;

    protected final static int STATE_DISCONNECTED = 0;
    protected final static int STATE_CONNECTING = -1;
    protected final static int STATE_CONNECTED = -2;
    protected final static int STATE_CONNECTED_AND_READY = -3;
    protected final static int STATE_DISCONNECTING = -4;
    protected final static int STATE_CLOSED = -5;

    protected final static int ATTEMPT_FILED = -1;
    protected final static int ATTEMPT_ENTER_DFU = 0;
    protected final static int ATTEMPT_SUCCESS = 1;


    private int connectionState = STATE_DISCONNECTED;
    private boolean descriptorWriteSuccess = false;
    private boolean statusRequestSuccess = false;
    private boolean hashRequestSuccess = false;
    private static final byte PACKET_STATE_WAITING = (byte) 0x00;
    private static final byte PACKET_STATE_RETRANSMIT = (byte) 0xAA;
    private static final byte PACKET_STATE_COMPLETE_FLASH = (byte) 0xCF;
    private byte packetState = PACKET_STATE_WAITING;

    private static final String[] BLUETOOTH_PERMISSIONS;

    static {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            BLUETOOTH_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            BLUETOOTH_PERMISSIONS = new String[]{Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN};
        }
    }

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null || !device.getAddress().equals(deviceAddress)) {
                return;
            }

            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            // Take action depending on new bond state
            if (action.equals(ACTION_BOND_STATE_CHANGED)) {
                bondState = intent.getIntExtra(EXTRA_BOND_STATE, ERROR);
                switch (bondState) {
                    case BOND_BONDING -> {
                        log(Log.WARN, "Bonding started");
                    }
                    case BOND_BONDED, BOND_NONE -> {
                        log(Log.WARN, "Bonding " + (bondState == BOND_BONDED));
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                }
            }
        }
    };

    @SuppressWarnings({"MissingPermission"})
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    connectionState = STATE_CONNECTED;

                    if (bondState == BOND_NONE || bondState == BOND_BONDED) {
                        if (bondState == BOND_BONDED && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                            waitFor(1600);
                        }
                        boolean result = gatt.discoverServices();
                        if (!result) {
                            connectionState = STATE_DISCONNECTING;
                            gatt.disconnect();
                            log(Log.ERROR, "DiscoverServices failed to start");
                        }
                    } else if (bondState == BOND_BONDING) {
                        log(Log.WARN, "Waiting for bonding to complete");
                        try {
                            synchronized (lock) {
                                while (bondState == BOND_BONDING)
                                    lock.wait();
                            }
                        } catch (final InterruptedException e) {
                            log(Log.ERROR, "Sleeping interrupted, " + e);
                        }
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    connectionState = STATE_DISCONNECTED;
                    clearServicesCache(gatt);
                    gatt.close();
                    stopSelf();
                }
            } else {
                connectionState = STATE_DISCONNECTING;
                gatt.disconnect();
                sendProgressBroadcastFailed();
            }
            // Notify waiting thread
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                connectionState = STATE_CONNECTED_AND_READY;
            } else {
                connectionState = STATE_DISCONNECTING;
                gatt.disconnect();
                sendProgressBroadcastFailed();
            }
            // Notify waiting thread
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            // Notify waiting thread
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            super.onCharacteristicChanged(gatt, characteristic, value);
            switch (value[0]) {
                case REGION_INFO_COMMAND -> {
                    byte[] hash = Arrays.copyOfRange(value, 10, 18);
                    if (value[1] == REGION_DAL) {
                        dalHash = bytesToHex(hash);
                        log(Log.VERBOSE, "Hash: " + dalHash);
                        hashRequestSuccess = true;
                    }
                }
                case FLASH_COMMAND -> packetState = value[1];
                case STATUS -> {
                    log(Log.WARN, value[2] == MODE_APPLICATION ? "Application Mode" : "Pairing Mode");
                    if (value[2] == MODE_APPLICATION) {
                        //Reset (0x00 for Pairing Mode, 0x01 for Application Mode)
                        writeCharacteristic(gatt, (byte) 0xFF, (byte) 0x00);
                    } else {
                        statusRequestSuccess = true;
                    }
                }
            }
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorWriteSuccess = true;
            } else {
                connectionState = STATE_DISCONNECTING;
                gatt.disconnect();
                sendProgressBroadcastFailed();
            }
            // Notify waiting thread
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    };

    // Handler that receives messages from the thread
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        @SuppressWarnings({"MissingPermission"})
        public void handleMessage(Message msg) {
            if (isPermissionGranted(BLUETOOTH_PERMISSIONS)) {
                sendProgressBroadcastStart();
                BluetoothGatt gatt = connect();
                if (gatt != null && connectionState == STATE_CONNECTED_AND_READY) {
                    log(Log.DEBUG, "Connected and ready");
                    int status = attemptPartialFlashing(gatt);
                    switch (status) {
                        case ATTEMPT_ENTER_DFU -> {
                            connectionState = STATE_DISCONNECTING;
                            gatt.disconnect();
                            sendProgressBroadcastAttemptDfu();
                        }
                        case ATTEMPT_FILED -> {
                            connectionState = STATE_DISCONNECTING;
                            gatt.disconnect();
                            sendProgressBroadcastFailed();
                        }
                    }
                } else {
                    sendProgressBroadcastAttemptDfu();
                    stopSelf(msg.arg1);
                }

            } else {
                log(Log.ERROR, "No Permission Granted");
                sendProgressBroadcastFailed();
                stopSelf(msg.arg1);
            }
        }
    }

    @Override
    public void onCreate() {
        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work doesn't disrupt our UI.
        HandlerThread thread = new HandlerThread("ServiceStartArguments",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        Looper serviceLooper = thread.getLooper();
        serviceHandler = new ServiceHandler(serviceLooper);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log(Log.WARN, "service starting, startId: " + startId);

        registerReceiver(bondStateReceiver, new IntentFilter(ACTION_BOND_STATE_CHANGED));
        deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        filePath = intent.getStringExtra(EXTRA_FILE_PATH);

        // For each start request, send a message to start a job and deliver the
        // start ID so we know which request we're stopping when we finish the job
        Message msg = serviceHandler.obtainMessage();
        msg.arg1 = startId;
        serviceHandler.sendMessage(msg);

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }

    @Override
    public void onDestroy() {
        log(Log.WARN, "service done");
        connectionState = STATE_CLOSED;
        unregisterReceiver(bondStateReceiver);
    }

    @SuppressWarnings({"MissingPermission"})
    private boolean isPartialFlashingServiceAvailable(BluetoothGatt gatt) {
        log(Log.INFO, "Checking if the flashing services is available...");
        BluetoothGattService partialFlashingService = gatt.getService(PARTIAL_FLASHING_SERVICE_UUID);
        if (partialFlashingService == null) {
            return false;

        }

        partialFlashingCharacteristic = partialFlashingService.getCharacteristic(PARTIAL_FLASHING_CHARACTERISTIC_UUID);
        if (partialFlashingCharacteristic == null) {
            gatt.disconnect();
            return false;
        }

        if (!gatt.setCharacteristicNotification(partialFlashingCharacteristic, true)) {
            log(Log.WARN, "setCharacteristicNotification FALSE");
            return false;
        }

        BluetoothGattDescriptor descriptor = partialFlashingCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(descriptor)) {
            log(Log.WARN, "writeDescriptor FALSE");
            return false;
        }

        try {
            synchronized (lock) {
                while (!descriptorWriteSuccess)
                    lock.wait();
            }
        } catch (final InterruptedException e) {
            log(Log.ERROR, "Sleeping interrupted " + e);
        }
        return true;
    }

    private boolean sendStatusRequest(BluetoothGatt gatt) {
        log(Log.INFO, "Send status request...");

        boolean res = writeCharacteristic(gatt, (byte) 0xEE);
        try {
            synchronized (lock) {
                while (!statusRequestSuccess)
                    lock.wait();
            }
        } catch (final InterruptedException e) {
            log(Log.ERROR, "Sleeping interrupted " + e);
        }
        return res;
    }

    private boolean sendHashRequest(BluetoothGatt gatt) {
        log(Log.INFO, "Send hash request...");

        boolean res = writeCharacteristic(gatt, (byte) 0x00, (byte) 0x01);
        try {
            synchronized (lock) {
                while (!hashRequestSuccess)
                    lock.wait();
            }
        } catch (final InterruptedException e) {
            log(Log.ERROR, "Sleeping interrupted " + e);
        }
        return res;
    }


    private int attemptPartialFlashing(BluetoothGatt gatt) {
        if (!isPartialFlashingServiceAvailable(gatt)) {
            return ATTEMPT_ENTER_DFU;
        }

        if (!sendStatusRequest(gatt)) {
            return ATTEMPT_ENTER_DFU;
        }

        if (!sendHashRequest(gatt)) {
            return ATTEMPT_ENTER_DFU;
        }

        if (dalHash == null) {
            return ATTEMPT_ENTER_DFU;
        }
        long startTime = SystemClock.elapsedRealtime();
        int count = 0;
        int numOfLines = 0;


        try {
            log(Log.DEBUG, "Attempt Partial Flashing");
            log(Log.DEBUG, "File path: " + filePath);

            HexUtils hex = new HexUtils(filePath);

            // шукаємо індекс магічного magic number після йкого в нас записаний хеш
            int magicIndex = hex.searchForData(PXT_MAGIC);
            log(Log.DEBUG, "Magic Index: " + magicIndex);

            // Якщо не знайдено пробуємо шукати для нового типу прошивки
            if (magicIndex == -1) {
                magicIndex = hex.searchForDataRegEx(UPY_MAGIC) - 3;
                log(Log.DEBUG, "Magic Index: " + magicIndex);
            }

            // тут якщо ми щось таки знайшли працюємо далі, а якщо ні, то ніякого часткого перепрошиття не може бути
            if (magicIndex > -1) {

                log(Log.DEBUG, "Found magic");

                // дивимося довжину рядка за цим індексом,

                int record_length = hex.getRecordDataLengthFromIndex(magicIndex);
                log(Log.DEBUG, "Length of record: " + record_length);

                //вістановлюємо відступ, до розміщення хешу, чому в іншому випадку 0? UPY_MAGIC це частина хешу?
                int magic_offset = (record_length == 64) ? 32 : 0;

                // тут вже ми отримуємо сам хеш
                String hashes = hex.getDataFromIndex(magicIndex + ((record_length == 64) ? 0 : 1)); // Size of rows

                log(Log.DEBUG, "Hashes: " + hashes);

                //Обрізаємо рядок з хешем і порівнюємо з отриманим з борда
                if (!hashes.substring(magic_offset, magic_offset + 16).equals(dalHash)) {
                    log(Log.WARN, "No match: " + hashes.substring(magic_offset, magic_offset + 16) + " " + (dalHash));
                    return ATTEMPT_ENTER_DFU;
                }

                //Flashing

                numOfLines = hex.numOfLines() - magicIndex;
                log(Log.VERBOSE, "Total lines: " + numOfLines);

                String hexData;
                int packetNum = 0;
                int lineCount = 0;

                log(Log.DEBUG, "Enter flashing loop");

                while (true) {
                    // Timeout if total is > 30 seconds
                    if (SystemClock.elapsedRealtime() - startTime > 60000) {
                        log(Log.WARN, "Partial flashing has timed out");
                        return ATTEMPT_ENTER_DFU;
                    }

                    // Get next data to write
                    hexData = hex.getDataFromIndex(magicIndex + lineCount);

                    // Check if EOF
                    if (hex.getRecordTypeFromIndex(magicIndex + lineCount) != 0) break;

                    // Split into bytes
                    int offsetToSend = 0;
                    if (count == 0) {
                        offsetToSend = hex.getRecordAddressFromIndex(magicIndex + lineCount);
                    }

                    if (count == 1) {
                        offsetToSend = hex.getSegmentAddress(magicIndex + lineCount);
                    }

                    byte[] chunk = HexUtils.recordToByteArray(hexData, offsetToSend, packetNum);

                    // Write without response
                    // Wait for previous write to complete
                    writeCharacteristic(gatt, chunk);

                    // Sleep after 4 packets
                    count++;
                    if (count == 4) {
                        count = 0;

                        // Send broadcast while waiting
                        int percent = Math.round((float) 100 * ((float) (lineCount) / (float) (numOfLines)));
                        sendProgressBroadcast(percent);

                        long timeout = SystemClock.elapsedRealtime();
                        try {
                            while (packetState == PACKET_STATE_WAITING) {
                                synchronized (lock) {
                                    lock.wait(5000);
                                }
                                // Timeout if longer than 5 seconds
                                if ((SystemClock.elapsedRealtime() - timeout) > 5000) {
                                    return ATTEMPT_FILED;
                                }
                            }
                        } catch (final InterruptedException e) {
                            log(Log.ERROR, "Sleeping interrupted, " + e);
                        }

                        packetState = PACKET_STATE_WAITING;
                    } else {
                        //waitFor(5);
                        Thread.sleep(5);
                    }

                    // If notification is retransmit -> retransmit last block.
                    // Else set start of new block
                    if (packetState == PACKET_STATE_RETRANSMIT) {
                        lineCount = lineCount - 4;
                    } else {
                        // Next line
                        lineCount = lineCount + 1;
                    }

                    // Always increment packet #
                    packetNum = packetNum + 1;

                }

                // Write End of Flash packet
                byte[] endOfFlashPacket = {(byte) 0x02};
                writeCharacteristic(gatt, endOfFlashPacket);

                // Finished Writing
                log(Log.DEBUG, "Flash Complete");
                packetState = PACKET_STATE_COMPLETE_FLASH;
                sendProgressBroadcast(100);
                sendProgressBroadcastComplete();

                // Time execution
                long endTime = SystemClock.elapsedRealtime();
                long elapsedMilliSeconds = endTime - startTime;
                double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                log(Log.DEBUG, "Flash Time: " + (float) elapsedSeconds + " seconds");

                return ATTEMPT_SUCCESS;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return ATTEMPT_ENTER_DFU;
    }

    @SuppressWarnings({"MissingPermission"})
    private boolean writeCharacteristic(BluetoothGatt gatt, byte... data) {
        partialFlashingCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        partialFlashingCharacteristic.setValue(data);
        return gatt.writeCharacteristic(partialFlashingCharacteristic);
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = String.format("%02X", b);
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private boolean isPermissionGranted(String... permissions) {
        for (String permission : permissions) {
            boolean granted = ContextCompat.checkSelfPermission(getApplicationContext(), permission) == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"MissingPermission"})
    private BluetoothGatt connect() {
        log(Log.DEBUG, "Connecting to the device...");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }

        connectionState = STATE_CONNECTING;
        BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
        bondState = device.getBondState();

        BluetoothGatt gatt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_2M_MASK);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(this, false, gattCallback);
        }

        // We have to wait until the device is connected and services are discovered
        // Connection error may occur as well.
        try {
            synchronized (lock) {
                while (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED)
                    lock.wait();
            }
        } catch (final InterruptedException e) {
            log(Log.ERROR, "Sleeping interrupted " + e);
        }
        return gatt;
    }

    protected void clearServicesCache(BluetoothGatt gatt) {
        try {
            //noinspection JavaReflectionMemberAccess
            final Method refresh = gatt.getClass().getMethod("refresh");
            //noinspection ConstantConditions
            final boolean success = (boolean) refresh.invoke(gatt);
            log(Log.DEBUG, "Refreshing result: " + success);
        } catch (final Exception e) {
            log(Log.ERROR, "An exception occurred while refreshing device. " + e);
        }
        waitFor(DELAY_TO_CLEAR_CACHE);
    }

    protected void waitFor(final long millis) {
        synchronized (lock) {
            try {
                log(Log.DEBUG, "Wait for " + millis + " millis");
                lock.wait(millis);
            } catch (final InterruptedException e) {
                log(Log.ERROR, "Sleeping interrupted, " + e);
            }
        }
    }

    private void sendProgressBroadcast(final int progress) {
        final Intent broadcast = new Intent(BROADCAST_PROGRESS);
        broadcast.putExtra(EXTRA_PROGRESS, progress);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastStart() {
        log(Log.ASSERT, "Sending progress broadcast start");
        final Intent broadcast = new Intent(BROADCAST_START);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastComplete() {
        log(Log.ASSERT, "Sending progress broadcast complete");
        final Intent broadcast = new Intent(BROADCAST_COMPLETE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastFailed() {
        log(Log.ASSERT, "Sending progress broadcast failed");
        final Intent broadcast = new Intent(BROADCAST_PF_FAILED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastAttemptDfu() {
        log(Log.ASSERT, "Sending progress broadcast attempt DFU");
        final Intent broadcast = new Intent(BROADCAST_PF_ATTEMPT_DFU);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    public void log(int priority, @NonNull String message) {
        Log.println(priority, TAG, "### " + Thread.currentThread().getId() + " # " + message);
    }
}