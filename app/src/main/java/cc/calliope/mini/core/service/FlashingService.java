package cc.calliope.mini.core.service;

import static android.app.Activity.RESULT_OK;
import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.Notification.INFO;
import static cc.calliope.mini.utils.Constants.MINI_V1;
import static cc.calliope.mini.utils.Constants.MINI_V2;
import static cc.calliope.mini.utils.Constants.UNIDENTIFIED;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import cc.calliope.mini.R;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.utils.FileUtils;
import cc.calliope.mini.utils.Preference;
import cc.calliope.mini.utils.Settings;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;

import cc.calliope.mini.utils.irmHexUtils;

public class FlashingService extends LifecycleService{
    private static final String TAG = "FlashingService";
    private static boolean isThisServiceRunning = false;
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private String currentAddress;
    private String currentPattern;
    private int currentVersion;
    private String currentPath;

    private State currentState;

    private static final int CONNECTION_TIMEOUT = 5000; // 5 seconds
    private Handler handler;
    private Runnable timeoutRunnable;

    private record HexToDfu(String path, int size) {
    }

    private final Observer<State> stateObserver = new Observer<>() {
        @Override
        public void onChanged(State state) {
            if (state == null) {
                return;
            }
            currentState = state;
            int type = state.getType();

            if (type == State.STATE_FLASHING ||
                type == State.STATE_ERROR) {
                handler.removeCallbacks(timeoutRunnable); // Remove the timeout callback
            }
        }
    };

    private final Observer<Progress> progressObserver = new Observer<>() {
        @Override
        public void onChanged(Progress progress) {
            if (progress == null) {
                return;
            }

            int value = progress.getValue();

            if (value == Progress.PROGRESS_DISCONNECTING) {
                stopSelf();
            }
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();

        ApplicationStateHandler.getStateLiveData().observe(this, stateObserver);
        currentState = ApplicationStateHandler.getStateLiveData().getValue();

        ApplicationStateHandler.getProgressLiveData().observe(this, progressObserver);

        ApplicationStateHandler.getErrorLiveData().observe(this, error -> {
            if (error != null) {
                int code = error.getCode();
                String message = error.getMessage();

                if (code == 4110) {
                    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                        Utils.log(Log.ERROR, TAG, "Bluetooth not enabled");
                    } else {
                        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(currentAddress);
                        device.createBond();
                    }
                } else {
                    Utils.log(Log.ERROR, TAG, "ERROR: " + code + " " + message);
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ApplicationStateHandler.getStateLiveData().removeObserver(stateObserver);
        ApplicationStateHandler.getProgressLiveData().removeObserver(progressObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Utils.log(Log.DEBUG, TAG, "FlashingService started");

//        if (currentState.getType() != State.STATE_IDLE) {
//            Utils.log(Log.ASSERT, TAG, "State: " + currentState.getType());
//            Utils.log(Log.INFO, TAG, "Service is already running.");
//            return START_NOT_STICKY;
//        }

        // TODO: are we need it?
        if (isThisServiceRunning) {
            Utils.log(Log.INFO, TAG, "Service is already running.");
        //    return START_STICKY;
        }

        // TODO: are we need it?
        if (isServiceRunning()) {
            Utils.log(Log.INFO, TAG, "Some flashing service is already running.");
            return START_NOT_STICKY; // Service will not be restarted
        }

        if(getPath(intent) && getDevice()) {
            int fileVersion = Utils.getFileVersion(currentPath);
            if((fileVersion == 2 && currentVersion == MINI_V1) || (fileVersion == 1 && currentVersion == MINI_V2)){
                ApplicationStateHandler.updateState(State.STATE_READY);
                ApplicationStateHandler.updateNotification(ERROR, getString(R.string.flashing_version_mismatch));
                return START_NOT_STICKY;
            }

            ApplicationStateHandler.updateNotification(INFO, "Flashing in progress. Please wait...");
            initFlashing();
        } else {
            ApplicationStateHandler.updateState(State.STATE_IDLE);
            ApplicationStateHandler.updateNotification(ERROR, getString(R.string.error_no_connected));
        }

        isThisServiceRunning = true;
        return START_NOT_STICKY;
    }

    private class LegacyDfuResultReceiver extends ResultReceiver {
        public LegacyDfuResultReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == RESULT_OK) {
                boolean isSuccess = resultData.getBoolean("result");
                if (isSuccess) {
                    startFlashing();
                } else {
                    Utils.log(ERROR, TAG, "DFU failed");
                }
            }
        }
    }

    // Check if the service is already running
    private boolean isServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (LegacyDfuService.class.getName().equals(service.service.getClassName()) ||
                    DfuService.class.getName().equals(service.service.getClassName())) {
                Utils.log(Log.ERROR, TAG, service.service.getClassName() + " is already running.");
                return true;
            }
        }
        return false;
    }

    private boolean getPath(Intent intent) {
        String path = intent.getStringExtra(Constants.EXTRA_FILE_PATH);
        if(path != null && !path.isEmpty()){
            currentPath = path;
        }
        if (currentPath == null || currentPath.isEmpty()) {
            Utils.log(Log.ERROR, TAG, "File path is empty or null. Service will stop.");
            stopSelf();
            return false;
        }

        // Save the path to preferences
        Preference.putString(getApplicationContext(), Constants.CURRENT_FILE_PATH, currentPath);
        return true;
    }

    private boolean getDevice() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        currentAddress = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "");
        currentPattern = preferences.getString(Constants.CURRENT_DEVICE_PATTERN, "ZUZUZ");
        currentVersion = preferences.getInt(Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED);

        if (!isValidBluetoothMAC(currentAddress)) {
            Utils.log(Log.ERROR, TAG, "Device address is incorrect. Service will stop.");
            stopSelf();
            return false;
        }

        if (currentVersion == UNIDENTIFIED) {
            Utils.log(Log.ERROR, TAG, "Device version is incorrect. Service will stop.");
            stopSelf();
            return false;
        }

        return true;
    }

    private void initFlashing(){
        if (!Utils.isBluetoothEnabled()) {
            Utils.log(Log.WARN, TAG, "Bluetooth not enabled or flashing already in progress. Service will stop.");
            return;
        }

        // Initialize the handler and timeout runnable
        handler = new Handler(Looper.getMainLooper());
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if(currentVersion == MINI_V1) {
                    ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.flashing_timeout_v1));
                } else {
                    ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.flashing_timeout_v2));
                }
            }
        };

        // Start the 5 second timeout countdown
        handler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT);


        if (Settings.isPartialFlashingEnable(this)) {
            // TODO: Implement partial flashing
        } else {
            if(currentVersion == MINI_V1) {
                startDfuControlService();
            } else {
                startFlashing();
            }
        }
    }

    private void startDfuControlService() {
        Utils.log(TAG, "Starting DfuControl Service...");

        LegacyDfuResultReceiver resultReceiver = new LegacyDfuResultReceiver(new Handler());

        // Start the service
        Intent service = new Intent(this, LegacyDfuService.class);
        service.putExtra(Constants.CURRENT_DEVICE_ADDRESS, currentAddress);
        service.putExtra("resultReceiver", resultReceiver);
        startService(service);
    }

    public boolean isValidBluetoothMAC(String macAddress) {
        if (macAddress == null) {
            Utils.log(Log.ERROR, TAG, "MAC address is null");
            return false;
        }

        String regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$";

        if (macAddress.matches(regex)) {
            Utils.log(Log.INFO, TAG, "Valid Bluetooth MAC address: " + macAddress);
            return true;
        } else {
            Utils.log(Log.INFO, TAG, "Invalid Bluetooth MAC address: " + macAddress);
            return false;
        }
    }


    @SuppressWarnings("deprecation")
    private void startFlashing() {
        Utils.log(Log.INFO, TAG, "Starting DFU Service...");

        HexToDfu hexToDFU = universalHexToDFU(currentPath, currentVersion);
        String hexPath = hexToDFU.path;
        int hexSize = hexToDFU.size;

        Utils.log(Log.DEBUG, TAG, "Path: " + hexPath);
        Utils.log(Log.DEBUG, TAG, "Size: " + hexSize);

        if (hexSize == -1) {
            return;
        }

        if (currentVersion == MINI_V1) {
            new DfuServiceInitiator(currentAddress)
                    .setDeviceName(currentPattern)
                    .setPrepareDataObjectDelay(300L)
                    .setNumberOfRetries(NUMBER_OF_RETRIES)
                    .setRebootTime(REBOOT_TIME)
                    .setForceDfu(true)
                    .setKeepBond(true)
                    .setMbrSize(0x1000)
                    .setBinOrHex(DfuBaseService.TYPE_APPLICATION, hexPath)
                    .start(this, DfuService.class);
        } else {
            String initPacketPath;
            String zipPath;

            try {
                initPacketPath = createDFUInitPacket(hexSize);
                zipPath = createDFUZip(initPacketPath, hexPath);
            } catch (IOException e) {
                Utils.log(Log.ERROR, TAG, "Failed to create init packet");
                e.printStackTrace();
                return;
            }

            if (zipPath == null) {
                Utils.log(Log.ERROR, TAG, "Failed to create ZIP");
                return;
            }

            new DfuServiceInitiator(currentAddress)
                    .setDeviceName(currentPattern)
                    .setPrepareDataObjectDelay(300L)
                    .setNumberOfRetries(NUMBER_OF_RETRIES)
                    .setRebootTime(REBOOT_TIME)
                    .setKeepBond(true)
                    .setPacketsReceiptNotificationsEnabled(true)
                    .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                    .setZip(zipPath)
                    .start(this, DfuService.class);
        }
    }

    private HexToDfu universalHexToDFU(String inputPath, int hardwareType) {
        Utils.log(Log.VERBOSE, TAG, "universalHexToDFU");
        try {
            FileInputStream fis = new FileInputStream(inputPath);
            int fileSize = Integer.valueOf(FileUtils.getFileSize(inputPath));
            byte[] bs = new byte[fileSize];
            int i = 0;
            i = fis.read(bs);

            Utils.log(Log.VERBOSE, TAG, "universalHexToDFU - read file");

            irmHexUtils irmHexUtil = new irmHexUtils();
            int hexBlock = hardwareType == MINI_V1
                    ? irmHexUtils.irmHexBlock01
                    : irmHexUtils.irmHexBlock03;
            if ( !irmHexUtil.universalHexToApplicationHex( bs, hexBlock)) {
                return new HexToDfu(null, -1);
            }
            byte [] dataHex = irmHexUtil.resultHex;
            int application_size = irmHexUtil.resultDataSize;
            Utils.log(Log.VERBOSE, TAG, "universalHexToDFU - Finished parsing HEX");

            try {
                File hexToFlash = new File(this.getCacheDir() + "/application.hex");
                if (hexToFlash.exists()) {
                    hexToFlash.delete();
                }
                hexToFlash.createNewFile();

                FileOutputStream outputStream = new FileOutputStream(hexToFlash);
                outputStream.write( dataHex);
                outputStream.flush();

                // Should return from here
                Utils.log(Log.VERBOSE, TAG, hexToFlash.getAbsolutePath());

                Utils.log(Log.VERBOSE, TAG, "universalHexToDFU - Finished");
                return new HexToDfu(hexToFlash.getAbsolutePath(), application_size);
            } catch (IOException e) {
                e.printStackTrace();
            }

        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found.");
            e.printStackTrace();
        } catch (IOException e) {
            Log.e(TAG, "IO Exception.");
            e.printStackTrace();
        }

        // Should not reach this
        return new HexToDfu(null, -1);
    }

    private String createDFUInitPacket(int hexLength) throws IOException {
        ByteArrayOutputStream outputInitPacket;
        outputInitPacket = new ByteArrayOutputStream();

        Utils.log(Log.VERBOSE, TAG, "DFU App Length: " + hexLength);

        outputInitPacket.write("microbit_app".getBytes()); // "microbit_app"
        outputInitPacket.write(new byte[]{0x1, 0, 0, 0});  // Init packet version
        outputInitPacket.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(hexLength).array());  // App size
        outputInitPacket.write(new byte[]{0, 0, 0, 0x0});  // Hash Size. 0: Ignore Hash
        outputInitPacket.write(new byte[]{
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0
        }); // Hash

        // Write to temp file
        File initPacket = new File(this.getCacheDir() + "/application.dat");
        if (initPacket.exists()) {
            initPacket.delete();
        }
        initPacket.createNewFile();

        FileOutputStream outputStream;
        outputStream = new FileOutputStream(initPacket);
        outputStream.write(outputInitPacket.toByteArray());
        outputStream.flush();

        // Should return from here
        return initPacket.getAbsolutePath();
    }

    /**
     * Create zip for DFU
     */
    private String createDFUZip(String... srcFiles) throws IOException {
        byte[] buffer = new byte[1024];

        File zipFile = new File(getCacheDir() + "/update.zip");
        if (zipFile.exists()) {
            if (zipFile.delete()) {
                if (!zipFile.createNewFile()) {
                    return null;
                }
            } else {
                return null;
            }
        }

        FileOutputStream fileOutputStream = new FileOutputStream(getCacheDir() + "/update.zip");
        ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream);

        for (String file : srcFiles) {

            File srcFile = new File(file);
            FileInputStream fileInputStream = new FileInputStream(srcFile);
            zipOutputStream.putNextEntry(new ZipEntry(srcFile.getName()));

            int length;
            while ((length = fileInputStream.read(buffer)) > 0) {
                zipOutputStream.write(buffer, 0, length);
            }

            zipOutputStream.closeEntry();
            fileInputStream.close();

        }

        // close the ZipOutputStream
        zipOutputStream.close();

        return getCacheDir() + "/update.zip";
    }
}