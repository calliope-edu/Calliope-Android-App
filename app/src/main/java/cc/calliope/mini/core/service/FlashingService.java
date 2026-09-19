package cc.calliope.mini.core.service;

import static android.app.Activity.RESULT_OK;
import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.utils.file.FileVersion.VERSION_2;
import static cc.calliope.mini.utils.file.FileVersion.VERSION_3;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.bluetooth.BluetoothDevice;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Consumer;
import androidx.lifecycle.LifecycleService;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cc.calliope.mini.ui.activity.NotificationActivity;

import cc.calliope.mini.utils.file.FirmwareZipCreator;
import cc.calliope.mini.utils.hex.HexParser;
import cc.calliope.mini.utils.hex.InitPacket;
import cc.calliope.mini.R;
import cc.calliope.mini.core.bluetooth.BleUuids;
import cc.calliope.mini.core.bluetooth.BoardGeneration;
import cc.calliope.mini.core.state.AppMode;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.FlashEvent;
import cc.calliope.mini.core.state.FlashMode;
import cc.calliope.mini.core.state.FlashResult;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.RepoObserve;
import cc.calliope.mini.utils.file.FileUtils;
import cc.calliope.mini.utils.file.FileVersion;
import cc.calliope.mini.utils.settings.Settings;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.bluetooth.BluetoothUtils;
import cc.calliope.mini.core.service.partialflashing.PartialFlashingService;
import no.nordicsemi.android.dfu.DfuDeviceSelector;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.internal.scanner.BootloaderScannerFactory;


public class FlashingService extends LifecycleService {
    private static final String TAG = "FlashingService";
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private static final long LEGACY_DFU_REBOOT_DELAY_MS = 3000L;
    /**
     * How long to look for the advertising bootloader. Normally it shows up
     * within a second of the 3 s reboot pause. The long ceiling covers the
     * failure mode of an nRF51 board running a program built WITHOUT
     * Bluetooth pairing: entered through the DFU service over an
     * unencrypted link, its bootloader (Nordic SDK 8) only advertises
     * DIRECTED to the address the phone used for the trigger — about 60 s —
     * then reboots into the program. A phone whose stack rotates its random
     * address right after connecting (Samsung / Android 15+) never sees
     * those packets. A program that requires pairing hands the bootloader
     * the phone's IRK instead, and it advertises normally (verified on the
     * same phone). Seeing the board back as its program ends the wait early
     * with a dedicated error.
     */
    private static final long LEGACY_BOOTLOADER_SCAN_MS = 70_000L;
    /** Ignore "board back as program" sightings this early — it may still be rebooting. */
    private static final long LEGACY_BOOTLOADER_GRACE_MS = 5_000L;
    /** Sentinel from the scan: the board came back as its program, DFU is unreachable. */
    private static final String BOOTLOADER_LOST = "";
    private static final String NOTIFICATION_CHANNEL_ID = "flashing_service_channel";
    private static final int NOTIFICATION_ID = 201;
    /** Root of the per-session work directories under cacheDir. */
    private static final String WORK_ROOT = "flash";
    private String currentAddress;
    private String currentPattern;
    private BoardGeneration boardGeneration = BoardGeneration.UNKNOWN;
    private String currentPath;
    /**
     * This session's own directory for application.bin / .dat / update.zip.
     * The file names are fixed by what Nordic DFU expects inside the zip, so
     * uniqueness has to come from the directory: a fixed cacheDir path let an
     * overlapping or crashed flash clobber the files of the next one.
     */
    private File workDir;
    private boolean forceFullDfu = false;

    // True while this instance owns the flash session (claimed through
    // AppStateRepository.beginFlash). The started service is a process-wide
    // singleton and every terminal path stops it, so the flag dies with the
    // instance; it also rejects duplicate starts while a job is running.
    private boolean flashingJobActive = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService backgroundExecutor;

    // The session's single terminal event. Whoever finishes the flash
    // (DfuService, PartialFlashingService or handleError here) posts it;
    // this service's job ends with it.
    private final Consumer<FlashEvent> flashEventObserver = event -> {
        if (!flashingJobActive || !(event instanceof FlashEvent.Done done)) {
            return;
        }
        if (done.getResult() instanceof FlashResult.Failure failure) {
            Log.e(TAG, "Flash failed: " + failure.getCode() + " " + failure.getMessage());
        } else {
            Log.d(TAG, "Flash completed");
        }
        flashingJobActive = false;
        stopSelf();
    };

    @SuppressWarnings("MissingPermission")
    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();
        backgroundExecutor = Executors.newSingleThreadExecutor();

        // The started service is STARTED for its whole life, so collection
        // spans it.
        RepoObserve.flashEvents(this, flashEventObserver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FlashingService destroyed");
        mainHandler.removeCallbacksAndMessages(null);
        // The session is over (Done stops this service), DFU no longer reads the zip.
        if (workDir != null) {
            FileUtils.deleteRecursively(workDir);
        }
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

        android.app.Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.flashing_notification_title))
                .setSmallIcon(R.drawable.ic_notification_flash)
                .setContentIntent(NotificationActivity.contentIntent(this))
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

        // Claim the process-wide flash mutex. Refused only if another flash
        // session is live (e.g. a DfuService still winding down).
        forceFullDfu = intent.getBooleanExtra(FlashLauncher.EXTRA_FORCE_FULL_DFU, false);
        FlashMode initialMode = !forceFullDfu && Settings.isPartialFlashingEnable(this)
                ? FlashMode.PARTIAL : FlashMode.FULL_DFU;
        if (!AppStateRepository.beginFlash(initialMode)) {
            AppStateRepository.updateNotification(ERROR, R.string.error_flashing_in_progress);
            stopSelf();
            return START_NOT_STICKY;
        }
        flashingJobActive = true;
        // We hold the mutex, so nothing else can be using the work area.
        FileUtils.deleteRecursively(new File(getCacheDir(), WORK_ROOT));

        // From here on every refusal goes through handleError(), which ends
        // the session and stops the service — otherwise the foreground
        // service (started in onCreate) would leak with no work to do.
        if (!isBluetoothEnabled()) {
            return START_NOT_STICKY;
        }

        String message = getString(R.string.flashing_process_starting);
        AppStateRepository.updateNotification(Notification.INFO, message);

        if (!loadDeviceInfo(intent)) {
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

    /** The target travels in the intent (see FlashLauncher), not through SharedPreferences. */
    private boolean loadDeviceInfo(Intent intent) {
        currentAddress = intent.getStringExtra(FlashLauncher.EXTRA_DEVICE_ADDRESS);
        currentPattern = intent.getStringExtra(FlashLauncher.EXTRA_DEVICE_NAME);

        if (!BluetoothUtils.isValidBluetoothMAC(currentAddress)) {
            Log.e(TAG, "Device address is incorrect");
            handleError(getString(R.string.error_device_address_incorrect));
            return false;
        }

        boardGeneration = BoardGeneration.fromPref(
                intent.getIntExtra(FlashLauncher.EXTRA_BOARD_GENERATION, BoardGeneration.UNKNOWN.getPrefValue()));
        if (boardGeneration == BoardGeneration.UNKNOWN) {
            Log.e(TAG, "Device version is incorrect");
            handleError(getString(R.string.error_device_version_incorrect));
            return false;
        }

        return true;
    }

    private boolean loadFilePath(Intent intent) {
        currentPath = intent.getStringExtra(FlashLauncher.EXTRA_FILE_PATH);

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
        if (fileVersion == VERSION_3 && boardGeneration == BoardGeneration.NRF51) {
            Log.e(TAG, "Flashing version mismatch: V3-only file on V2 board");
            handleError(getString(R.string.flashing_version_mismatch));
            return false;
        }
        if (fileVersion == VERSION_2 && boardGeneration == BoardGeneration.NRF52) {
            Log.e(TAG, "Flashing version mismatch: V1/V2-only file on V3 board");
            handleError(getString(R.string.flashing_version_mismatch));
            return false;
        }

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
                int code = resultData.getInt(PartialFlashingService.KEY_RESULT, PartialFlashingService.RESULT_FAILED);
                switch (code) {
                    case PartialFlashingService.RESULT_SUCCESS ->
                        // PartialFlashingService already finished the session;
                        // the Done event stops this service.
                            Log.d(TAG, "Partial flashing completed");
                    case PartialFlashingService.RESULT_ATTEMPT_DFU -> {
                        // The board or the hex declined partial flashing;
                        // nothing was written. The normal path.
                        Log.i(TAG, "Partial flashing declined, continuing with full DFU");
                        handleFullFlashing();
                    }
                    default -> {
                        // A real failure mid-upload leaves the program region
                        // half-written. A full DFU rewrites all of it, so it
                        // is the recovery, not a blind retry — and if the board
                        // is gone, DFU fails with a proper connection error.
                        Log.w(TAG, "Partial flashing failed, recovering with full DFU");
                        handleFullFlashing();
                    }
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
        // Either the configured mode or the partial-flash fallback: from here
        // on the session is full DFU (legacy trigger + Nordic, or Nordic only).
        AppStateRepository.flashMode(FlashMode.FULL_DFU);
        switch (boardGeneration) {
            case NRF51 -> startDfuControlService();
            case NRF52 -> prepareFirmwareAsync(this::startDfu);
            default -> {
                Log.e(TAG, "Unsupported board generation: " + boardGeneration);
                handleError(String.format(getString(R.string.error_unsupported_board_version), boardGeneration.getPrefValue()));
            }
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
        service.putExtra(LegacyDfuService.EXTRA_DEVICE_ADDRESS, currentAddress);
        service.putExtra(LegacyDfuService.EXTRA_RESULT_RECEIVER, resultReceiver);
        startService(service);
    }

    private String prepareFirmwareZip() {
        try {
            // Prepare firmware file
            HexParser parser = new HexParser(currentPath);
            byte[] firmware = parser.getCalliopeBin(boardGeneration);

            workDir = new File(new File(getCacheDir(), WORK_ROOT), UUID.randomUUID().toString());
            if (!workDir.mkdirs()) {
                Log.e(TAG, "Failed to create work directory " + workDir);
                return null;
            }

            String firmwarePath = new File(workDir, "application.bin").getAbsolutePath();
            if (!FileUtils.writeFile(firmwarePath, firmware)) {
                Log.e(TAG, "Failed to write firmware to file");
                return null;
            }

            // Prepare init packet
            InitPacket initPacket = new InitPacket(boardGeneration);
            byte[] initData = initPacket.encode(firmware);

            String initPacketPath = new File(workDir, "application.dat").getAbsolutePath();
            if (!FileUtils.writeFile(initPacketPath, initData)) {
                Log.e(TAG, "Failed to write init packet to file");
                return null;
            }

            // Create ZIP
            FirmwareZipCreator zipCreator = new FirmwareZipCreator(new File(workDir, "update.zip"), firmwarePath, initPacketPath);
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
        prepareFirmwareAsync(zipPath -> {
            try {
                backgroundExecutor.execute(() -> {
                    String target = findLegacyBootloader();
                    mainHandler.post(() -> {
                        if (BOOTLOADER_LOST.equals(target)) {
                            Log.e(TAG, "Board rebooted into its program before DFU could connect");
                            handleError(getString(R.string.error_legacy_bootloader_unreachable));
                            return;
                        }
                        startDfuLegacy(target, zipPath);
                    });
                });
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Service stopped before the bootloader scan");
            }
        });
    }

    private void startDfuLegacy(String targetAddress, String zipPath) {
        new DfuServiceInitiator(targetAddress)
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
                .start(this, DfuService.class);
    }

    /**
     * Where is the bootloader advertising? After the 0x01 trigger the nRF51
     * bootloader (Nordic SDK 8+) may keep the application's address or, when
     * entered through the DFU service the way LegacyDfuService does it, use
     * the last byte incremented by one. Nordic's library only scans for that
     * inside its own buttonless flow; with forceDfu it connects straight to
     * the address it was given and times out if the bootloader moved. So
     * look for the bootloader with the library's own scanner (same address
     * or +1, filtered by the legacy DFU service UUID) and hand DFU the address
     * that actually advertises. Blocking — call off the main thread.
     */
    @SuppressLint("MissingPermission")
    private String findLegacyBootloader() {
        Log.d(TAG, "Scanning for the legacy DFU bootloader (" + LEGACY_BOOTLOADER_SCAN_MS + " ms)...");
        LegacyBootloaderSelector selector = new LegacyBootloaderSelector();
        String found = BootloaderScannerFactory
                .getScanner(currentAddress, BleUuids.LEGACY_DFU_SERVICE)
                .searchUsing(selector, LEGACY_BOOTLOADER_SCAN_MS);
        if (selector.applicationSeen) {
            return BOOTLOADER_LOST;
        }
        if (found == null) {
            Log.w(TAG, "Bootloader not found — trying the application address " + currentAddress);
            return currentAddress;
        }
        Log.i(TAG, "Bootloader found at " + found
                + (found.equalsIgnoreCase(currentAddress) ? "" : " (application address " + currentAddress + ")"));
        return found;
    }

    /**
     * Accepts the board at its application address or at that address + 1,
     * but only while it advertises as the bootloader (legacy DFU service
     * UUID or the "DfuTarg" name in the advertisement) — a board that has
     * rebooted back into its application must not be handed to DFU. The
     * library's default scan filters (legacy DFU service UUID plus a
     * catch-all) are kept: a MAC filter would only match public addresses
     * and the board's is random static.
     */
    private static final class LegacyBootloaderSelector implements DfuDeviceSelector {
        private final long startedAt = SystemClock.elapsedRealtime();
        /** The board advertised as its program again: the bootloader is gone. */
        volatile boolean applicationSeen = false;

        @Override
        public boolean matches(@NonNull BluetoothDevice device, int rssi, @NonNull byte[] scanRecord,
                               @NonNull String originalAddress, @NonNull String incrementedAddress) {
            String address = device.getAddress();
            boolean ours = originalAddress.equalsIgnoreCase(address) || incrementedAddress.equalsIgnoreCase(address);
            if (!ours) return false;
            boolean bootloader = advertisesLegacyDfu(scanRecord);
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            Log.d(TAG, "Board seen at " + address + " after " + elapsed + " ms: "
                    + (bootloader ? "bootloader" : "not the bootloader (" + advertisedName(scanRecord) + ")"));
            if (bootloader) return true;
            if (elapsed > LEGACY_BOOTLOADER_GRACE_MS) {
                // Ends the scan; findLegacyBootloader() reads the flag.
                applicationSeen = true;
                return true;
            }
            return false;
        }
    }

    /** Legacy DFU service UUID as it appears in an AD structure (little-endian). */
    private static final byte[] LEGACY_DFU_UUID_LE = {
            (byte) 0x23, (byte) 0xD1, (byte) 0xBC, (byte) 0xEA, (byte) 0x5F, (byte) 0x78, (byte) 0x23, (byte) 0x15,
            (byte) 0xDE, (byte) 0xEF, (byte) 0x12, (byte) 0x12, (byte) 0x30, (byte) 0x15, (byte) 0x00, (byte) 0x00,
    };

    /** True if the raw advertisement lists the legacy DFU service or is named "DfuTarg". */
    static boolean advertisesLegacyDfu(byte[] scanRecord) {
        int i = 0;
        while (i + 1 < scanRecord.length) {
            int len = scanRecord[i] & 0xFF;
            if (len == 0) break;
            int type = scanRecord[i + 1] & 0xFF;
            int dataStart = i + 2;
            int dataLen = len - 1;
            if (dataStart + dataLen > scanRecord.length) break;
            if (type == 0x06 || type == 0x07) { // incomplete / complete list of 128-bit UUIDs
                for (int off = dataStart; off + 16 <= dataStart + dataLen; off += 16) {
                    boolean same = true;
                    for (int k = 0; k < 16; k++) {
                        if (scanRecord[off + k] != LEGACY_DFU_UUID_LE[k]) { same = false; break; }
                    }
                    if (same) return true;
                }
            } else if (type == 0x08 || type == 0x09) { // shortened / complete local name
                if ("DfuTarg".equals(new String(scanRecord, dataStart, dataLen, java.nio.charset.StandardCharsets.UTF_8))) {
                    return true;
                }
            }
            i += len + 1;
        }
        return false;
    }

    private static String advertisedName(byte[] scanRecord) {
        int i = 0;
        while (i + 1 < scanRecord.length) {
            int len = scanRecord[i] & 0xFF;
            if (len == 0) break;
            int type = scanRecord[i + 1] & 0xFF;
            if ((type == 0x08 || type == 0x09) && i + 1 + len <= scanRecord.length) {
                return new String(scanRecord, i + 2, len - 1, java.nio.charset.StandardCharsets.UTF_8);
            }
            i += len + 1;
        }
        return "?";
    }

    private void handleError(String message) {
        AppStateRepository.updateNotification(ERROR, message);
        // Ends the session: the resulting Done event stops this service.
        AppStateRepository.finishFlash(new FlashResult.Failure(AppMode.Error.NO_CODE, message));
    }
}