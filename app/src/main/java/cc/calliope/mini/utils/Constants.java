package cc.calliope.mini.utils;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.UUID;

public class Constants {
    public static final String EXTRA_DEVICE = "cc.calliope.mini.EXTRA_DEVICE";
    public static final String EXTRA_FILE_PATH = "cc.calliope.mini.EXTRA_FILE_PATH";
    public static final String EXTRA_NUMB_ATTEMPTS = "cc.calliope.mini.EXTRA_NUMB_ATTEMPTS";
    public static final String CURRENT_FILE_PATH = "cc.calliope.mini.CURRENT_FILE_PATH";
    public static final String CURRENT_DEVICE_ADDRESS = "cc.calliope.mini.CURRENT_DEVICE_ADDRESS";
    public static final String CURRENT_DEVICE_PATTERN = "cc.calliope.mini.CURRENT_DEVICE_PATTERN";
    public static final String CURRENT_DEVICE_VERSION = "cc.calliope.mini.CURRENT_DEVICE_VERSION";
    public static final int UNIDENTIFIED = 0;
    /**
     * Version 1.x, 2.0, 2,1
     * <a href="https://calliope-mini.github.io/v10/">Version 1.x</a>
     * <a href="https://calliope-mini.github.io/v20/">Version 2.0</a>
     * <a href="https://calliope-mini.github.io/v21/">Version 2.1</a>
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
    public static final UUID DFU_CONTROL_SERVICE_UUID = UUID.fromString("E95D93B0-251D-470A-A062-FA1922DFA9A8");
    public static final UUID DFU_CONTROL_CHARACTERISTIC_UUID = UUID.fromString("E95D93B1-251D-470A-A062-FA1922DFA9A8");
    public static final UUID SECURE_DFU_SERVICE_UUID = UUID.fromString("0000FE59-0000-1000-8000-00805F9B34FB");
}
