package cc.calliope.mini;

import static cc.calliope.mini.service.DfuControlService.MINI_V1;
import static cc.calliope.mini.service.DfuControlService.UNIDENTIFIED;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

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

import cc.calliope.mini.service.DfuControlService;
import cc.calliope.mini.service.DfuService;
import cc.calliope.mini.service.PartialFlashingService;
import cc.calliope.mini.utils.FileUtils;
import cc.calliope.mini.utils.Settings;
import cc.calliope.mini.utils.StaticExtras;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;

import cc.calliope.mini.utils.irmHexUtils;

public class FlashingService extends FlashingBaseService {
    private static final String TAG = "FlashingService";
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private String currentAddress;
    private String currentPattern;
    private String currentPath;
    private int progress = -10;

    private record HexToDfu(String path, int size) {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.log(Log.ASSERT, TAG, "onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Utils.log(Log.ASSERT, TAG, "onDestroy");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Utils.log(Log.ASSERT, TAG, "onStartCommand");

        if (Utils.isBluetoothEnabled() && progress < 0) {
            String path = intent.getStringExtra(StaticExtras.EXTRA_FILE_PATH);
            if(path != null && !path.isEmpty()) {
                currentPath = path;
            }
            Utils.log(Log.INFO, TAG, "File path: " + currentPath);

            getDevice();
            if (isValidBluetoothMAC(currentAddress)) {
                if (Settings.isPartialFlashingEnable(this)) {
                    startPartialFlashing();
                } else {
                    startDfuControlService();
                }
            } else {
                Utils.log(Log.WARN, TAG, "Bluetooth MAC incorrect");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onHardwareVersionReceived(int hardwareVersion) {
        Utils.log(Log.ASSERT, TAG, "Board version: " + hardwareVersion);
        startFlashing(hardwareVersion);
    }

    @Override
    public void onDfuAttempt() {
        startDfuControlService();
    }

    @Override
    public void onError(int code, String message) {
        Utils.log(Log.ASSERT, TAG, "ERROR: " + code + " " + message);
        if (code == 4110) {
            String msg = "Device not bound. Please bind the mini and try again.";
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Utils.log(Log.ERROR, TAG, "Bluetooth not enabled");
            } else {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(currentAddress);
                device.createBond();
            }
        }
    }

    @Override
    public void onProgressUpdate(int percent){
        progress = percent;
    }

    private void getDevice() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        currentAddress = preferences.getString(StaticExtras.DEVICE_ADDRESS, "");
        currentPattern = preferences.getString(StaticExtras.DEVICE_PATTERN, "ZUZUZ");

        Utils.log(Log.INFO, TAG, "Device: " + currentAddress + " " + currentPattern);
    }

    private void startPartialFlashing() {
        Utils.log(TAG, "Starting PartialFlashing Service...");

        Intent service = new Intent(this, PartialFlashingService.class);
        service.putExtra(PartialFlashingService.EXTRA_DEVICE_ADDRESS, currentAddress);
        service.putExtra(StaticExtras.EXTRA_FILE_PATH, currentPath); // a path or URI must be provided.
        startService(service);
    }

    private void startDfuControlService() {
        Utils.log(TAG, "Starting DfuControl Service...");

        Intent service = new Intent(this, DfuControlService.class);
        service.putExtra(StaticExtras.DEVICE_ADDRESS, currentAddress);
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
    private void startFlashing(@DfuControlService.HardwareVersion final int hardwareVersion) {
        Utils.log(Log.INFO, TAG, "Starting DFU Service...");

        if (hardwareVersion == UNIDENTIFIED) {
            Utils.log(Log.ERROR, TAG, "BOARD_UNIDENTIFIED");
            return;
        }

        HexToDfu hexToDFU = universalHexToDFU(currentPath, hardwareVersion);
        String hexPath = hexToDFU.path;
        int hexSize = hexToDFU.size;

        Utils.log(Log.DEBUG, TAG, "Path: " + hexPath);
        Utils.log(Log.DEBUG, TAG, "Size: " + hexSize);

        if (hexSize == -1) {
            return;
        }

        if (hardwareVersion == MINI_V1) {
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