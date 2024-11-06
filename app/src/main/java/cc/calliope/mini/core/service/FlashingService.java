package cc.calliope.mini.core.service;

import static android.app.Activity.RESULT_OK;
import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.Notification.INFO;
import static cc.calliope.mini.utils.Constants.MINI_V2;
import static cc.calliope.mini.utils.Constants.MINI_V3;
import static cc.calliope.mini.utils.Constants.UNIDENTIFIED;
import static cc.calliope.mini.utils.FileVersion.VERSION_2;
import static cc.calliope.mini.utils.FileVersion.VERSION_3;
import static no.nordicsemi.android.dfu.DfuBaseService.TYPE_APPLICATION;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import cc.calliope.mini.HexParser;
import cc.calliope.mini.InitPacket;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.utils.FileUtils;
import cc.calliope.mini.utils.FileVersion;
import cc.calliope.mini.utils.Preference;
import cc.calliope.mini.utils.Settings;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.Utils;
import kotlin.Unit;
import no.nordicsemi.android.dfu.DfuServiceInitiator;


public class FlashingService extends LifecycleService{
    private static final String TAG = "FlashingService";
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private String currentAddress;
    private String currentPattern;
    private int boardVersion;
    private String currentPath;

    private State currentState = new State(State.STATE_IDLE);

    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";


//    private static final int CONNECTION_TIMEOUT = 30000; // 3 seconds
//    private Handler handler;
//    private Runnable timeoutRunnable;


    private final Observer<State> stateObserver = new Observer<>() {
        @Override
        public void onChanged(State state) {
            if (state == null) {
                return;
            }
            currentState = state;
//            int type = state.getType();
//
//            if (type == State.STATE_FLASHING ||
//                type == State.STATE_ERROR) {
//                handler.removeCallbacks(timeoutRunnable); // Remove the timeout callback
//            }
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

    @SuppressWarnings("MissingPermission")
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

        if (currentState.getType() != State.STATE_IDLE) {
            Utils.log(Log.WARN, TAG, "Service is already running.");
            return START_NOT_STICKY;
        }

        if(getPath(intent) && getDevice()) {
            FileVersion fileVersion = FileUtils.getFileVersion(currentPath);
            if((fileVersion == VERSION_3 && boardVersion == MINI_V2) || (fileVersion == VERSION_2 && boardVersion == MINI_V3)){
                ApplicationStateHandler.updateState(State.STATE_IDLE);
                ApplicationStateHandler.updateNotification(ERROR, getString(R.string.flashing_version_mismatch));
                return START_NOT_STICKY;
            }

            ApplicationStateHandler.updateNotification(INFO, "Flashing in progress. Please wait...");
            initFlashing();
        } else {
            ApplicationStateHandler.updateState(State.STATE_IDLE);
            ApplicationStateHandler.updateNotification(ERROR, getString(R.string.error_no_connected));
        }

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
        boardVersion = preferences.getInt(Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED);

        if (!isValidBluetoothMAC(currentAddress)) {
            Utils.log(Log.ERROR, TAG, "Device address is incorrect. Service will stop.");
            stopSelf();
            return false;
        }

        if (boardVersion == UNIDENTIFIED) {
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
//
//        // Initialize the handler and timeout runnable
//        handler = new Handler(Looper.getMainLooper());
//        timeoutRunnable = new Runnable() {
//            @Override
//            public void run() {
//                if(currentVersion == MINI_V1) {
//                    ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.flashing_timeout_v1));
//                } else {
//                    ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.flashing_timeout_v2));
//                }
//            }
//        };
//
//        // Start the N second timeout countdown
//        handler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT);



        if (Settings.isPartialFlashingEnable(this)) {

        } else {
            if(boardVersion == MINI_V2) {
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

    public byte[] getCalliopeBin(int dataTypeParam, long startAddress, long endAddress) {
        HexParser parser = new HexParser(currentPath);
        List<Byte> bin = new ArrayList<>();
        parser.parse((address, data, dataType, isUniversal) -> {
            if (address >= startAddress && address < endAddress && (dataType == dataTypeParam || !isUniversal)) {
                for (byte b : data) {
                    bin.add(b);
                }
            }
            return Unit.INSTANCE;
        });

        // Android DFU library requires the firmware to be word-aligned
        int totalDataSize = bin.size();
        if (totalDataSize % 4 != 0) {
            int paddingSize = 4 - (totalDataSize % 4);
            for (int i = 0; i < paddingSize; i++) {
                bin.add((byte) 0xFF);
            }
        }

        byte[] binArray = new byte[bin.size()];
        for (int i = 0; i < bin.size(); i++) {
            binArray[i] = bin.get(i);
        }
        return binArray;
    }

    public byte[] getCalliopeV2Bin() {
        return getCalliopeBin(1, 0x18000L, 0x3C000L);
    }

    public byte[] getCalliopeV3Bin() {
        return getCalliopeBin(2, 0x1C000L, 0x77000L);
    }

    @SuppressWarnings("deprecation")
    private void startFlashing() {
        Utils.log(Log.INFO, TAG, "Starting DFU Service...");
        if (boardVersion == MINI_V2) {
            byte[] firmware = getCalliopeV2Bin();
            String firmwarePath = getCacheDir() + File.separator + "application.bin";

            if (firmware == null) {
                Utils.log(Log.ERROR, TAG, "Failed to convert HEX to DFU");
                ApplicationStateHandler.updateNotification(ERROR, "Failed to convert HEX to DFU");
                return;
            }

            if (!FileUtils.writeFile(firmwarePath, firmware)){
                Utils.log(Log.ERROR, TAG, "Failed to write firmware to file");
                ApplicationStateHandler.updateNotification(ERROR, "Failed to write firmware to file");
                return;
            }
            new DfuServiceInitiator(currentAddress)
                    .setDeviceName(currentPattern)
                    .setPrepareDataObjectDelay(300L)
                    .setNumberOfRetries(NUMBER_OF_RETRIES)
                    .setRebootTime(REBOOT_TIME)
                    .setForceDfu(true)
                    .setKeepBond(true)
                    .setMbrSize(0x1000)
                    .setBinOrHex(TYPE_APPLICATION, firmwarePath)
                    .start(this, DfuService.class);
        } else {
            byte[] firmware = getCalliopeV3Bin();
            String firmwarePath = getCacheDir() + File.separator + "application.bin";
            String initPacketPath =  getCacheDir() + File.separator + "application.dat";

            if (firmware == null) {
                Utils.log(Log.ERROR, TAG, "Failed to convert HEX to DFU");
                ApplicationStateHandler.updateNotification(ERROR, "Failed to convert HEX to DFU");
                return;
            }

            if (!FileUtils.writeFile(firmwarePath, firmware)){
                Utils.log(Log.ERROR, TAG, "Failed to write firmware to file");
                ApplicationStateHandler.updateNotification(ERROR, "Failed to write firmware to file");
                return;
            }

            InitPacket initPacket = new InitPacket(firmware.length);
            byte[] intData = initPacket.encode();
            if (!FileUtils.writeFile(initPacketPath, intData)){
                Utils.log(Log.ERROR, TAG, "Failed to write init packet to file");
                ApplicationStateHandler.updateNotification(ERROR, "Failed to write init packet to file");
                return;
            }

            Utils.log(Log.DEBUG, TAG, "Path: " + firmwarePath);
            Utils.log(Log.DEBUG, TAG, "Size: " + firmware.length);

            String zipPath;
            try {
                zipPath = createDFUZip(initPacketPath, firmwarePath);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            if (zipPath == null) {
                Utils.log(Log.ERROR, TAG, "Failed to create ZIP");
                ApplicationStateHandler.updateNotification(ERROR, "Failed to create ZIP");
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

        zipOutputStream.close();

        return getCacheDir() + "/update.zip";
    }
}