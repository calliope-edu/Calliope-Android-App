package cc.calliope.mini;

import static cc.calliope.mini.service.DfuControlService.MINI_V1;
import static cc.calliope.mini.service.DfuControlService.MINI_V2;
import static cc.calliope.mini.service.DfuControlService.UNIDENTIFIED;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import cc.calliope.mini.activity.FlashingActivity;
import cc.calliope.mini.service.DfuControlService;
import cc.calliope.mini.service.DfuService;
import cc.calliope.mini.utils.FileUtils;
import cc.calliope.mini.utils.StaticExtra;
import cc.calliope.mini.utils.Utils;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;

public class FlashingService extends FlashingBaseService {
    private static final String TAG = "TEST_TAG";
    private static final int NUMBER_OF_RETRIES = 3;
    private static final int REBOOT_TIME = 2000; // time required by the device to reboot, ms
    private String currentAddress;
    private String currentPattern;
    private String currentPath;

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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        Utils.log(Log.ASSERT, TAG, "onBind");
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Utils.log(Log.ASSERT, TAG, "onStartCommand");

        currentPath = intent.getStringExtra(StaticExtra.EXTRA_FILE_PATH);
        Utils.log(Log.INFO, TAG, "File path: " + currentPath);

        getDevice();
        if (isValidBluetoothMAC(currentAddress)) {
            startDfuControlService();
        } else {
            Utils.log(Log.WARN, TAG, "File path: " + currentPath);
        }

        return START_STICKY;
    }

    private void getDevice() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        currentAddress = preferences.getString(StaticExtra.DEVICE_ADDRESS, "");
        currentPattern = preferences.getString(StaticExtra.DEVICE_PATTERN, "ZUZUZ");

        Utils.log(Log.INFO, TAG, "Device: " + currentAddress + " " + currentPattern);
    }

    private void startDfuControlService() {
        Utils.log(TAG, "Starting DfuControl Service...");

        Intent service = new Intent(this, DfuControlService.class);
        service.putExtra(StaticExtra.DEVICE_ADDRESS, currentAddress);
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

    @Override
    public void onStartDfuService(int hardwareVersion) {
        Utils.log(Log.ASSERT, "DeviceInformation", "Board version: " + hardwareVersion);
        startFlashing(hardwareVersion);
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


    private HexToDfu universalHexToDFU(String inputPath, @DfuControlService.HardwareVersion int hardwareVersion) {
        FileInputStream fis;
        ByteArrayOutputStream outputHex;
        outputHex = new ByteArrayOutputStream();

        ByteArrayOutputStream test = new ByteArrayOutputStream();

        FileOutputStream outputStream;

        int application_size = 0;
        int next = 0;
        boolean records_wanted = true;
        boolean is_fat = false;
        boolean is_v2 = false;
        boolean uses_ESA = false;
        ByteArrayOutputStream lastELA = new ByteArrayOutputStream();
        ByteArrayOutputStream lastESA = new ByteArrayOutputStream();

        try {
            fis = new FileInputStream(inputPath);
            byte[] bs = new byte[Integer.valueOf(FileUtils.getFileSize(inputPath))];
            int i = 0;
            i = fis.read(bs);

            for (int b_x = 0; b_x < bs.length - 1; /* empty */) {

                // Get record from following bytes
                char b_type = (char) bs[b_x + 8];

                // Find next record start, or EOF
                next = 1;
                while ((b_x + next) < i && bs[b_x + next] != ':') {
                    next++;
                }

                // Switch type and determine what to do with this record
                switch (b_type) {
                    case 'A': // Block start
                        is_fat = true;
                        records_wanted = false;

                        // Check data for id
                        if (bs[b_x + 9] == '9' && bs[b_x + 10] == '9' && bs[b_x + 11] == '0' && bs[b_x + 12] == '0') {
                            records_wanted = (hardwareVersion == MINI_V1);
                        } else if (bs[b_x + 9] == '9' && bs[b_x + 10] == '9' && bs[b_x + 11] == '0' && bs[b_x + 12] == '1') {
                            records_wanted = (hardwareVersion == MINI_V1);
                        } else if (bs[b_x + 9] == '9' && bs[b_x + 10] == '9' && bs[b_x + 11] == '0' && bs[b_x + 12] == '3') {
                            records_wanted = (hardwareVersion == MINI_V2);
                        }
                        break;
                    case 'E':
                        break;
                    case '4':
                        ByteArrayOutputStream currentELA = new ByteArrayOutputStream();
                        currentELA.write(bs, b_x, next);

                        uses_ESA = false;

                        // If ELA has changed write
                        if (!currentELA.toString().equals(lastELA.toString())) {
                            lastELA.reset();
                            lastELA.write(bs, b_x, next);
                            Utils.log(Log.VERBOSE, TAG, "TEST ELA " + lastELA.toString());
                            outputHex.write(bs, b_x, next);
                        }

                        break;
                    case '2':
                        uses_ESA = true;

                        ByteArrayOutputStream currentESA = new ByteArrayOutputStream();
                        currentESA.write(bs, b_x, next);

                        // If ESA has changed write
                        if (!Arrays.equals(currentESA.toByteArray(), lastESA.toByteArray())) {
                            lastESA.reset();
                            lastESA.write(bs, b_x, next);
                            outputHex.write(bs, b_x, next);
                        }
                        break;
                    case '1':
                        // EOF
                        // Ensure KV storage is erased
                        if (hardwareVersion == MINI_V1) {
                            String kv_address = ":020000040003F7\n";
                            String kv_data = ":1000000000000000000000000000000000000000F0\n";
                            outputHex.write(kv_address.getBytes());
                            outputHex.write(kv_data.getBytes());
                        }

                        // Write final block
                        outputHex.write(bs, b_x, next);
                        break;
                    case 'D': // V2 section of Universal Hex
                        // Remove D
                        bs[b_x + 8] = '0';
                        // Find first \n. PXT adds in extra padding occasionally
                        int first_cr = 0;
                        while (bs[b_x + first_cr] != '\n') {
                            first_cr++;
                        }

                        // Skip 1 word records
                        // TODO: Pad this record for uPY FS scratch
                        if (bs[b_x + 2] == '1') break;

                        // Recalculate checksum
                        int checksum = (charToInt((char) bs[b_x + first_cr - 2]) * 16) + charToInt((char) bs[b_x + first_cr - 1]) + 0xD;
                        String checksum_hex = Integer.toHexString(checksum);
                        checksum_hex = "00" + checksum_hex.toUpperCase(); // Pad to ensure we have 2 characters
                        checksum_hex = checksum_hex.substring(checksum_hex.length() - 2);
                        bs[b_x + first_cr - 2] = (byte) checksum_hex.charAt(0);
                        bs[b_x + first_cr - 1] = (byte) checksum_hex.charAt(1);
                    case '3':
                    case '5':
                    case '0':
                        // Copy record to hex
                        // Record starts at b_x, next long
                        // Calculate address of record
                        int b_a = 0;
                        if (lastELA.size() > 0 && !uses_ESA) {
                            b_a = (charToInt((char) lastELA.toByteArray()[9]) << 12) | (charToInt((char) lastELA.toByteArray()[10]) << 8) | (charToInt((char) lastELA.toByteArray()[11]) << 4) | (charToInt((char) lastELA.toByteArray()[12]));
                            b_a = b_a << 16;
                        }
                        if (lastESA.size() > 0 && uses_ESA) {
                            b_a = (charToInt((char) lastESA.toByteArray()[9]) << 12) | (charToInt((char) lastESA.toByteArray()[10]) << 8) | (charToInt((char) lastESA.toByteArray()[11]) << 4) | (charToInt((char) lastESA.toByteArray()[12]));
                            b_a = b_a * 16;
                        }

                        int b_raddr = (charToInt((char) bs[b_x + 3]) << 12) | (charToInt((char) bs[b_x + 4]) << 8) | (charToInt((char) bs[b_x + 5]) << 4) | (charToInt((char) bs[b_x + 6]));
                        int b_addr = b_a | b_raddr;

                        int lower_bound = 0;
                        int upper_bound = 0;
                        //MICROBIT_V1 lower_bound = 0x18000; upper_bound = 0x38000;
                        //Memory range nRF51 (S130 v2.0.x) 0x0001B000 - 0x0003AC00 (127 kB)
                        //0x00014000 - 0x0003C800
                        if (hardwareVersion == MINI_V1) {
                            lower_bound = 0x18000;
                            upper_bound = 0x3BBFF;
                        }
                        //MICROBIT_V2 lower_bound = 0x27000; upper_bound = 0x71FFF;
                        //Memory range nRF52833 (S113 v7.0.x) Application area (incl. free space) 0x0001C000 - 0x00078000 (368 kB)
                        if (hardwareVersion == MINI_V2) {
                            lower_bound = 0x1C000;
                            upper_bound = 0x77000;
                        }

                        // Check for Cortex-M4 Vector Table
                        if (b_addr == 0x10 && bs[b_x + 41] != 'E' && bs[b_x + 42] != '0') { // Vectors exist
                            is_v2 = true;
                        }

                        if ((records_wanted || !is_fat) && b_addr >= lower_bound && b_addr < upper_bound) {

                            outputHex.write(bs, b_x, next);
                            // Add to app size
                            application_size = application_size + charToInt((char) bs[b_x + 1]) * 16 + charToInt((char) bs[b_x + 2]);
                        } else {
                            // Log.v(TAG, "TEST " + Integer.toHexString(b_addr) + " BA " + b_a + " LELA " + lastELA.toString() + " " + uses_ESA);
                            // test.write(bs, b_x, next);
                        }

                        break;
                    case 'C':
                    case 'B':
                        records_wanted = false;
                        break;
                    default:
                        Utils.log(Log.ERROR, TAG, "Record type not recognised; TYPE: " + b_type);
                }

                // Record handled. Move to next ':'
                if ((b_x + next) >= i) {
                    break;
                } else {
                    b_x = b_x + next;
                }

            }

            byte[] output = outputHex.toByteArray();
            byte[] testBytes = test.toByteArray();

            Utils.log(Log.VERBOSE, TAG, "Finished parsing HEX. Writing application HEX for flashing");

            try {
                File hexToFlash = new File(this.getCacheDir() + "/application.hex");
                if (hexToFlash.exists()) {
                    hexToFlash.delete();
                }
                hexToFlash.createNewFile();

                outputStream = new FileOutputStream(hexToFlash);
                outputStream.write(output);
                outputStream.flush();

                // Should return from here
                Utils.log(Log.VERBOSE, TAG, hexToFlash.getAbsolutePath());

                /*
                if(hardwareVersion == MICROBIT_V2 && (!is_v2 && !is_fat)) {
                    ret[1] = Integer.toString(-1); // Invalidate hex file
                }
                 */

                return new HexToDfu(hexToFlash.getAbsolutePath(), application_size);
            } catch (IOException e) {
                e.printStackTrace();
            }

        } catch (FileNotFoundException e) {
            Utils.log(Log.ERROR, TAG, "File not found.");
            e.printStackTrace();
        } catch (IOException e) {
            Utils.log(Log.ERROR, TAG, "IO Exception.");
            e.printStackTrace();
        }

        // Should not reach this
        return new HexToDfu(null, -1);
    }

    /**
     * Convert a HEX char to int
     */
    int charToInt(char in) {
        // 0 - 9
        if (in - '0' >= 0 && in - '0' < 10) {
            return (in - '0');
        }
        // A - F
        return in - 55;
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