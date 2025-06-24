package cc.calliope.mini.core.service;

import static android.app.Activity.RESULT_OK;
import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.State.STATE_ERROR;
import static cc.calliope.mini.utils.Constants.MINI_V2;
import static cc.calliope.mini.utils.Constants.MINI_V3;
import static cc.calliope.mini.utils.Constants.UNIDENTIFIED;
import static cc.calliope.mini.utils.file.FileVersion.VERSION_2;
import static cc.calliope.mini.utils.file.FileVersion.VERSION_3;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.lifecycle.LifecycleService;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import java.io.File;

import cc.calliope.mini.utils.file.FirmwareZipCreator;
import cc.calliope.mini.utils.hex.HexParser;
import cc.calliope.mini.utils.hex.InitPacket;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Error;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.utils.file.FileUtils;
import cc.calliope.mini.utils.file.FileVersion;
import cc.calliope.mini.utils.settings.Preference;
import cc.calliope.mini.utils.settings.Settings;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.dfu.DfuServiceInitiator;


public class FlashingService extends LifecycleService {
    private static final String TAG = "FlashingService";
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private String currentAddress;
    private String currentPattern;
    private int boardVersion;
    private String currentPath;

    private State currentState = new State(State.STATE_IDLE);

    private final Observer<State> stateObserver = state -> {
        if (state == null) {
            return;
        }
        currentState = state;
        if (state.getType() == STATE_ERROR) {
            Error error = ApplicationStateHandler.getErrorLiveData().getValue();
            if (error != null) {
                Log.e(TAG, "ERROR: " + error.getCode() + " " + error.getMessage());
            }
            Log.e(TAG, "FlashingService stopped");
            stopSelf();
        }
    };

    private final Observer<Progress> progressObserver = progress -> {
        if (progress == null) {
            return;
        }

        int value = progress.getValue();

        if (value == Progress.PROGRESS_DISCONNECTING) {
            stopSelf();
        }
    };

    @SuppressWarnings("MissingPermission")
    @Override
    public void onCreate() {
        super.onCreate();
        // Get the current state
        currentState = ApplicationStateHandler.getStateLiveData().getValue();

        // Observe the state and progress
        ApplicationStateHandler.getStateLiveData().observe(this, stateObserver);
        ApplicationStateHandler.getProgressLiveData().observe(this, progressObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FlashingService destroyed");
        ApplicationStateHandler.getStateLiveData().removeObserver(stateObserver);
        ApplicationStateHandler.getProgressLiveData().removeObserver(progressObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "FlashingService started");

        String message = getString(R.string.partial_flashing_starting);
        ApplicationStateHandler.updateNotification(Notification.INFO, message);

        if (!isBluetoothEnabled() || flashingInProgress()) {
            return START_NOT_STICKY;
        }

        if (!loadDeviceInfo()) {
            return START_NOT_STICKY;
        }

        if (!loadFilePath(intent)) {
            return START_NOT_STICKY;
        }

        if (!checkCompatibility()) {
            return START_NOT_STICKY;
        }

        initFlashing();
        return START_NOT_STICKY;
    }

    private boolean isBluetoothEnabled() {
        if (!Utils.isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled");
            handleError("Bluetooth is not enabled. Please enable it and try again.");
            return false;
        }
        return true;
    }

    private boolean flashingInProgress() {
        if (currentState.getType() == State.STATE_FLASHING) {
            Log.w(TAG, "Flashing is already in progress");
            return true;
        }
        return false;
    }

    private boolean loadDeviceInfo() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        currentAddress = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "");
        currentPattern = preferences.getString(Constants.CURRENT_DEVICE_PATTERN, "");

        if (!checkBluetoothMAC(currentAddress)) {
            Log.e(TAG, "Device address is incorrect");
            handleError("Device address is incorrect. Service will stop.");
            return false;
        }

        boardVersion = preferences.getInt(Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED);
        if (boardVersion == UNIDENTIFIED) {
            Log.e(TAG, "Device version is incorrect");
            handleError("Device version is incorrect. Service will stop.");
            return false;
        }

        return true;
    }

    private boolean loadFilePath(Intent intent) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        currentPath = intent.getStringExtra(Constants.EXTRA_FILE_PATH);
        if (currentPath == null || currentPath.isEmpty()) {
            currentPath = preferences.getString(Constants.CURRENT_FILE_PATH, "");
        } else {
            Preference.putString(getApplicationContext(), Constants.CURRENT_FILE_PATH, currentPath);
        }

        if (currentPath == null || currentPath.isEmpty()) {
            Log.e(TAG, "File path is missing");
            handleError("File path is missing. Service will stop.");
            return false;
        }

        return true;
    }

    private boolean checkCompatibility() {
        FileVersion fileVersion = FileUtils.getFileVersion(currentPath);

        if ((fileVersion == VERSION_3 && boardVersion == MINI_V2) ||
                (fileVersion == VERSION_2 && boardVersion == MINI_V3)) {
            Log.e(TAG, "Flashing version mismatch");
            handleError(getString(R.string.flashing_version_mismatch));
            return false;
        }

        return true;
    }

    public boolean checkBluetoothMAC(String macAddress) {
        if (macAddress == null) {
            Log.e(TAG, "MAC address is null");
            return false;
        }

        String regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$";
        if (!macAddress.matches(regex)) {
            Log.i(TAG, "Invalid Bluetooth MAC address: " + macAddress);
            return false;
        }

        Log.i(TAG, "MAC address: " + macAddress);
        return true;
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
                    startDfu();
                } else {
                    Log.e(TAG, "DFU failed");
                    handleError("DFU failed. Service will stop.");
                }
            }
        }
    }

    private class PartialFlashingInitReceiver extends ResultReceiver {
        public PartialFlashingInitReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == RESULT_OK) {
                boolean isSuccess = resultData.getBoolean("result");
                if (isSuccess) {
                    Log.d(TAG, "Partial flashing initiated");
                    startPartialFlashing();
                } else {
                    Log.e(TAG, "Failed to initiate partial flashing");
                    handleFullFlashing();
                }
            }
        }
    }

    private class PartialFlashingReceiver extends ResultReceiver {
        public PartialFlashingReceiver(Handler handler) {
            super(handler);
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            Log.d(TAG, "Partial flashing result received");
            if (resultCode == RESULT_OK) {
                boolean isSuccess = resultData.getBoolean("result");
                if (isSuccess) {
                    Log.d(TAG, "Partial flashing completed");
                    stopSelf();
                } else {
                    Log.e(TAG, "Partial flashing failed");
                    startDfu();
                }
            }
        }
    }

    private void startPartialFlashing() {
        PartialFlashingReceiver resultReceiver = new PartialFlashingReceiver(new Handler());

        Intent intent = new Intent(this, PartialFlashingService.class);
        intent.putExtra("filepath", currentPath);
        intent.putExtra("deviceAddress", currentAddress);
        intent.putExtra("resultReceiver", resultReceiver);
        startService(intent);
    }

    private void initFlashing() {
        if (Settings.isPartialFlashingEnable(this)) {
            handlePartialFlashing();
        } else {
            handleFullFlashing();
        }
    }

    private void handlePartialFlashing() {
        PartialFlashingInitReceiver resultReceiver = new PartialFlashingInitReceiver(new Handler());

        // Start the service
        Intent service = new Intent(this, PartialFlashingInitService.class);
        service.putExtra(Constants.CURRENT_DEVICE_ADDRESS, currentAddress);
        service.putExtra("resultReceiver", resultReceiver);
        startService(service);
    }

    private void handleFullFlashing() {
        if (boardVersion == MINI_V2) {
            startDfuControlService();
        } else if (boardVersion == MINI_V3) {
            startDfu();
        } else {
            Log.e(TAG, "Unsupported board version: " + boardVersion);
            handleError("Unsupported board version: " + boardVersion);
        }
    }

    private void startDfuControlService() {
        Log.d(TAG, "Starting DfuControl Service...");
        LegacyDfuResultReceiver resultReceiver = new LegacyDfuResultReceiver(new Handler());

        // Start the service
        Intent service = new Intent(this, LegacyDfuService.class);
        service.putExtra(Constants.CURRENT_DEVICE_ADDRESS, currentAddress);
        service.putExtra("resultReceiver", resultReceiver);
        startService(service);
    }

    private String prepareFirmwareZip() {
        // Prepare firmware file
        HexParser parser = new HexParser(currentPath);
        byte[] firmware = parser.getCalliopeBin(boardVersion);

        String firmwarePath = getCacheDir() + File.separator + "application.bin";
        if (!FileUtils.writeFile(firmwarePath, firmware)) {
            Log.e(TAG, "Failed to write firmware to file");
            return null;
        }

        // Prepare init packet
        InitPacket initPacket = new InitPacket(boardVersion);
        byte[] initData = initPacket.encode(firmware);

        String initPacketPath = getCacheDir() + File.separator + "application.dat";
        if (!FileUtils.writeFile(initPacketPath, initData)) {
            Log.e(TAG, "Failed to write init packet to file");
            return null;
        }

        // Create ZIP
        FirmwareZipCreator zipCreator = new FirmwareZipCreator(this, firmwarePath, initPacketPath);
        String zipPath = zipCreator.createZip();
        if (zipPath == null) {
            Log.e(TAG, "Failed to create ZIP");
        }

        return zipPath;
    }

    private void startDfu() {
        String zipPath = prepareFirmwareZip();
        if (zipPath == null) {
            Log.e(TAG, "Failed to prepare firmware ZIP");
            handleError("Failed to prepare firmware ZIP");
            return;
        }

        new DfuServiceInitiator(currentAddress)
                .setDeviceName(currentPattern)
                .setPrepareDataObjectDelay(300L)
                .setNumberOfRetries(NUMBER_OF_RETRIES)
                .setRebootTime(REBOOT_TIME)
                .setKeepBond(true)
                .setZip(zipPath)
                .start(this, DfuService.class);
    }

    private void handleError(String message) {
        ApplicationStateHandler.updateNotification(ERROR, message);
        ApplicationStateHandler.updateState(STATE_ERROR);
    }
}