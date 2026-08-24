package cc.calliope.mini.core.service;

import static android.app.Activity.RESULT_OK;
import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.State.STATE_ERROR;
import static cc.calliope.mini.utils.Constants.MINI_V2;
import static cc.calliope.mini.utils.Constants.MINI_V3;
import static cc.calliope.mini.utils.Constants.UNIDENTIFIED;
import static cc.calliope.mini.utils.file.FileVersion.VERSION_2;
import static cc.calliope.mini.utils.file.FileVersion.VERSION_3;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.util.Consumer;
import androidx.lifecycle.LifecycleService;
import androidx.core.util.Consumer;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cc.calliope.mini.ui.activity.NotificationActivity;

import cc.calliope.mini.utils.file.FirmwareZipCreator;
import cc.calliope.mini.utils.hex.HexParser;
import cc.calliope.mini.utils.hex.InitPacket;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.RepoObserve;
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
import cc.calliope.mini.core.service.partialflashing.PartialFlashingService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;


public class FlashingService extends LifecycleService {
    private static final String TAG = "FlashingService";
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private static final long LEGACY_DFU_REBOOT_DELAY_MS = 3000L;
    private static final String NOTIFICATION_CHANNEL_ID = "flashing_service_channel";
    private static final int NOTIFICATION_ID = 201;
    public static final String EXTRA_FORCE_FULL_DFU = "extra_force_full_dfu";
    private String currentAddress;
    private String currentPattern;
    private int boardVersion;
    private String currentPath;
    private boolean forceFullDfu = false;

    // True while this instance is orchestrating a flash. Unlike the global
    // state LiveData it cannot be stale: the started service is a process-wide
    // singleton and every terminal path stops it, so the flag dies with the
    // instance. Guarding the observers with it keeps sticky STATE_ERROR /
    // PROGRESS_DISCONNECTING values replayed from a previous session from
    // killing a freshly started service.
    private boolean flashingJobActive = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService backgroundExecutor;

    private final Consumer<State> stateObserver = state -> {
        if (state == null || !flashingJobActive) {
            return;
        }
        if (state.getType() == STATE_ERROR) {
            Error error = AppStateRepository.getError().getValue();
            if (error != null) {
                Log.e(TAG, "ERROR: " + error.getCode() + " " + error.getMessage());
            }
            Log.e(TAG, "FlashingService stopped");
            stopSelf();
        }
    };

    private final Consumer<Progress> progressObserver = progress -> {
        if (progress == null || !flashingJobActive) {
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
        startForegroundWithNotification();
        backgroundExecutor = Executors.newSingleThreadExecutor();

        // Observe the state and progress from the repository. The started
        // service is STARTED for its whole life, so collection spans it.
        RepoObserve.state(this, stateObserver);
        RepoObserve.progress(this, progressObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FlashingService destroyed");
        mainHandler.removeCallbacksAndMessages(null);
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.flashing_notification_title),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent notificationIntent = new Intent(this, NotificationActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        android.app.Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.flashing_notification_title))
                .setSmallIcon(R.drawable.ic_notification_flash)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "FlashingService started");

        if (intent == null) {
            Log.w(TAG, "Null intent, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        // A duplicate start while a flash is running lands on this same
        // instance; it stays alive to finish the active job, so no stopSelf.
        if (flashingInProgress()) {
            return START_NOT_STICKY;
        }

        // From here on every refusal goes through handleError(), which stops
        // the service — otherwise the foreground service (started in
        // onCreate) would leak with no work to do.
        if (!isBluetoothEnabled()) {
            return START_NOT_STICKY;
        }

        String message = getString(R.string.flashing_process_starting);
        ApplicationStateHandler.updateNotification(Notification.INFO, message);

        if (!loadDeviceInfo()) {
            return START_NOT_STICKY;
        }

        if (!loadFilePath(intent)) {
            return START_NOT_STICKY;
        }

        if (!checkCompatibility()) {
            return START_NOT_STICKY;
        }

        forceFullDfu = intent.getBooleanExtra(EXTRA_FORCE_FULL_DFU, false);
        flashingJobActive = true;
        initFlashing();
        return START_NOT_STICKY;
    }

    private boolean isBluetoothEnabled() {
        if (!Utils.isBluetoothEnabled(this)) {
            Log.e(TAG, "Bluetooth is not enabled");
            handleError(getString(R.string.error_bluetooth_not_enabled_service));
            return false;
        }
        return true;
    }

    private boolean flashingInProgress() {
        if (flashingJobActive) {
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
            handleError(getString(R.string.error_device_address_incorrect));
            return false;
        }

        boardVersion = preferences.getInt(Constants.CURRENT_DEVICE_VERSION, UNIDENTIFIED);
        if (boardVersion == UNIDENTIFIED) {
            Log.e(TAG, "Device version is incorrect");
            handleError(getString(R.string.error_device_version_incorrect));
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
            handleError(getString(R.string.error_file_path_missing));
            return false;
        }

        return true;
    }

    private boolean checkCompatibility() {
        FileVersion fileVersion = FileUtils.getFileVersion(currentPath);

        // Three hex kinds we care about:
        //   - VERSION_2: V1/V2-only (DAL) — nRF51, max flash 0x40000
        //   - VERSION_3: V3-only — nRF52, line 1 carries the V3-specific
        //     data record pattern
        //   - UNIVERSAL: targets both — either via the Microsoft "C0DE"
        //     line-2 marker or detected by the deeper scan in
        //     FileUtils.getFileVersion (data records ≥ 0x40000 or
        //     block-marker record types 0x0A–0x0E)
        //
        // Reject the two genuinely-incompatible directions; UNIVERSAL
        // passes on both boards. Previously the deeper scan didn't exist
        // and universal MicroPython hexes were misclassified as VERSION_2,
        // tripping the V2-on-V3 rejection — now they classify as UNIVERSAL
        // and pass.
        if (fileVersion == VERSION_3 && boardVersion == MINI_V2) {
            Log.e(TAG, "Flashing version mismatch: V3-only file on V2 board");
            handleError(getString(R.string.flashing_version_mismatch));
            return false;
        }
        if (fileVersion == VERSION_2 && boardVersion == MINI_V3) {
            Log.e(TAG, "Flashing version mismatch: V1/V2-only file on V3 board");
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
                    // Wait for device to reboot into DFU mode before starting Nordic DFU
                    mainHandler.postDelayed(FlashingService.this::startDfuLegacy, LEGACY_DFU_REBOOT_DELAY_MS);
                } else {
                    Log.e(TAG, "DFU failed");
                    handleError(getString(R.string.error_dfu_failed));
                }
            } else {
                Log.e(TAG, "Legacy DFU unexpected resultCode: " + resultCode);
                handleError(getString(R.string.error_dfu_failed));
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
                    Log.w(TAG, "Partial flashing failed, falling back to DFU");
                    handleFullFlashing();
                }
            }
        }
    }

    private void initFlashing() {
        if (!forceFullDfu && Settings.isPartialFlashingEnable(this)) {
            handlePartialFlashing();
        } else {
            handleFullFlashing();
        }
    }

    private void handlePartialFlashing() {
        Log.d(TAG, "Starting partial flashing service");
        PartialFlashingReceiver resultReceiver = new PartialFlashingReceiver(mainHandler);

        Intent service = new Intent(this, PartialFlashingService.class);
        service.putExtra(PartialFlashingService.EXTRA_DEVICE_ADDRESS, currentAddress);
        service.putExtra(PartialFlashingService.EXTRA_FILE_PATH, currentPath);
        service.putExtra(PartialFlashingService.EXTRA_RESULT_RECEIVER, resultReceiver);
        startService(service);
    }

    private void handleFullFlashing() {
        if (boardVersion == MINI_V2) {
            startDfuControlService();
        } else if (boardVersion == MINI_V3) {
            prepareFirmwareAsync(this::startDfu);
        } else {
            Log.e(TAG, "Unsupported board version: " + boardVersion);
            handleError(String.format(getString(R.string.error_unsupported_board_version), boardVersion));
        }
    }

    /**
     * Runs {@link #prepareFirmwareZip()} off the main thread and delivers the
     * resulting zip path back on the main thread. Prevents ANR on large hex files.
     */
    private void prepareFirmwareAsync(Consumer<String> onReady) {
        backgroundExecutor.execute(() -> {
            String zipPath;
            try {
                zipPath = prepareFirmwareZip();
            } catch (Throwable t) {
                Log.e(TAG, "Firmware preparation crashed", t);
                mainHandler.post(() -> handleError(getString(R.string.error_failed_prepare_firmware_zip)));
                return;
            }

            final String finalZipPath = zipPath;
            mainHandler.post(() -> {
                if (finalZipPath == null) {
                    Log.e(TAG, "Failed to prepare firmware ZIP");
                    handleError(getString(R.string.error_failed_prepare_firmware_zip));
                    return;
                }
                onReady.accept(finalZipPath);
            });
        });
    }

    private void startDfuControlService() {
        Log.d(TAG, "Starting DfuControl Service...");
        LegacyDfuResultReceiver resultReceiver = new LegacyDfuResultReceiver(mainHandler);

        // Start the service
        Intent service = new Intent(this, LegacyDfuService.class);
        service.putExtra(Constants.CURRENT_DEVICE_ADDRESS, currentAddress);
        service.putExtra("resultReceiver", resultReceiver);
        startService(service);
    }

    private String prepareFirmwareZip() {
        try {
            // Prepare firmware file
            HexParser parser = new HexParser(currentPath);
            byte[] firmware = parser.getCalliopeBin(boardVersion);

            String firmwarePath = new File(getCacheDir(), "application.bin").getAbsolutePath();
            if (!FileUtils.writeFile(firmwarePath, firmware)) {
                Log.e(TAG, "Failed to write firmware to file");
                return null;
            }

            // Prepare init packet
            InitPacket initPacket = new InitPacket(boardVersion);
            byte[] initData = initPacket.encode(firmware);

            String initPacketPath = new File(getCacheDir(), "application.dat").getAbsolutePath();
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
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory while preparing firmware", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error while preparing firmware", e);
            return null;
        }
    }

    private void startDfu(String zipPath) {
        new DfuServiceInitiator(currentAddress)
                .setDeviceName(currentPattern)
                .setPrepareDataObjectDelay(300L)
                .setNumberOfRetries(NUMBER_OF_RETRIES)
                .setRebootTime(REBOOT_TIME)
                .setKeepBond(true)
                .setZip(zipPath)
                .start(this, DfuService.class);
    }

    /**
     * Start DFU for V2 (Legacy) devices that are already in DFU bootloader mode
     * after being triggered by LegacyDfuService.
     * Note: Bond is removed in LegacyDfuService to prevent Nordic DFU 2.7.0+
     * from waiting for Service Changed indication (which V2 bootloader doesn't send).
     */
    private void startDfuLegacy() {
        prepareFirmwareAsync(zipPath ->
                new DfuServiceInitiator(currentAddress)
                        .setDeviceName(currentPattern)
                        .setMtu(23)
                        .setNumberOfRetries(NUMBER_OF_RETRIES)
                        .setRebootTime(REBOOT_TIME)
                        .setKeepBond(false)
                        .setForceDfu(true)
                        .setForceScanningForNewAddressInLegacyDfu(true)
                        // V2 (nRF51) needs PRN enabled - it can't handle data sent too fast
                        .setPacketsReceiptNotificationsEnabled(true)
                        .setPacketsReceiptNotificationsValue(6)
                        .setZip(zipPath)
                        .start(this, DfuService.class)
        );
    }

    private void handleError(String message) {
        ApplicationStateHandler.updateNotification(ERROR, message);
        ApplicationStateHandler.updateState(STATE_ERROR);
        // The state observer only reacts while a flash job is active, so a
        // pre-flight failure must stop the service explicitly.
        stopSelf();
    }
}