package cc.calliope.mini;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;


public class App extends Application {
    private static final String BLOCKS_DIR = "BLOCKS";
    private static final String CUSTOM_DIR = "CUSTOM";
    private static final String MAKECODE_DIR = "MAKECODE";
    private static final String CARDBOARD_CONTROL_DIR = "CARDBOARD_CONTROL";
    private static final String CARDBOARD_FACE_DIR = "CARDBOARD_FACE";
    private static final String PYTHON_DIR = "PYTHON";

    private static final String[] RAW_FILES = {
        "blocks",
        "one_time_pairing",
        "demo_lofi_control",
        "demo_lofi_face",
        "demo_snake",
        "demo_matrix",
        "demo_dodge",
        "demo_effects",
        "demo_pong"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Dynamic color is intentionally NOT applied: the brand-seeded scheme in
        // colors.xml is pinned so the dark theme always shows the classic Calliope
        // navy (#131720) used on the official sites, instead of the wallpaper tint.
        cc.calliope.mini.core.state.AppStateRepository.initialize(this);
        migrateSnakeFromMakecodeToPython();
        copyFilesToInternalStorage();

        // CheckService is started from BaseActivity.onResume — starting a
        // background service here crashes on API 26+ when the process is
        // spawned in the background (broadcast, content provider, etc.).
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
    }

    private void migrateSnakeFromMakecodeToPython() {
        File oldFile = new File(getFilesDir(), MAKECODE_DIR + File.separator + "demo_snake.hex");
        if (oldFile.exists()) {
            if (oldFile.delete()) {
                Log.d("App", "Migrated demo_snake.hex: removed old copy from MAKECODE");
            }
        }
    }

    /** Prefs holding the CRC of every bundled file we copied, plus the APK stamp of the last sync. */
    private static final String RAW_PREFS = "raw_files";
    private static final String PREF_SYNC_STAMP = "sync_stamp";
    private static final String PREF_CRC_PREFIX = "crc_";

    /**
     * Copy the bundled hex files into the editors' directories.
     *
     * A missing file is always copied. An existing one is only reconsidered
     * after the APK changed (its lastUpdateTime differs from the stamp of
     * the previous sync), and then replaced when the bundled content
     * differs from what we copied last time — unless the user replaced the
     * file with their own (its content no longer matches our last copy),
     * which we leave alone. Before this, a raw file updated in a new build
     * never reached an existing install: the copy was skipped whenever the
     * target existed, until the app data was cleared.
     */
    private void copyFilesToInternalStorage() {
        SharedPreferences prefs = getSharedPreferences(RAW_PREFS, MODE_PRIVATE);
        long apkStamp = apkLastUpdateTime();
        boolean apkChanged = prefs.getLong(PREF_SYNC_STAMP, -1L) != apkStamp;
        SharedPreferences.Editor editor = prefs.edit();

        for (String fileName : RAW_FILES) {
            File libraryDir = new File(getFilesDir(), getTargetDirectory(fileName));
            if (!libraryDir.exists()) {
                libraryDir.mkdirs();
            }
            syncRawFile(libraryDir, fileName, apkChanged, prefs, editor);
        }

        editor.putLong(PREF_SYNC_STAMP, apkStamp).apply();
    }

    private void syncRawFile(File libraryDir, String fileName, boolean apkChanged,
                             SharedPreferences prefs, SharedPreferences.Editor editor) {
        String fullFileName = fileName + getFileExtension(fileName);
        File file = new File(libraryDir, fullFileName);
        int resourceId = getRawResourceId(fileName);
        if (resourceId == 0) {
            Log.w("App", "Resource not found for file: " + fileName);
            return;
        }
        String crcKey = PREF_CRC_PREFIX + fileName;

        try {
            if (file.exists()) {
                if (!apkChanged) {
                    return; // same APK as last time: nothing can have changed
                }
                String bundledCrc = crc32(getResources().openRawResource(resourceId));
                String copiedCrc = prefs.getString(crcKey, null);
                if (bundledCrc.equals(copiedCrc)) {
                    return; // bundled content unchanged since our last copy
                }
                if (copiedCrc != null && !copiedCrc.equals(crc32(new FileInputStream(file)))) {
                    Log.w("App", fullFileName + " was replaced by the user, keeping it");
                    return;
                }
                Log.i("App", "Bundled " + fullFileName + " changed, updating the copy");
            }

            try (InputStream inputStream = getResources().openRawResource(resourceId);
                 FileOutputStream outputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int length;
                CRC32 crc = new CRC32();
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                    crc.update(buffer, 0, length);
                }
                editor.putString(crcKey, Long.toHexString(crc.getValue()));
                Log.d("App", "Copied " + fullFileName + " to internal storage");
            }
        } catch (IOException e) {
            Log.e("App", "Error copying file: " + fullFileName, e);
        }
    }

    private long apkLastUpdateTime() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return -1L;
        }
    }

    /** CRC32 of a stream, which is closed afterwards. */
    private static String crc32(InputStream in) throws IOException {
        try (InputStream stream = in) {
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) > 0) {
                crc.update(buffer, 0, length);
            }
            return Long.toHexString(crc.getValue());
        }
    }

    private String getTargetDirectory(String fileName) {
        return switch (fileName) {
            case "blocks" -> BLOCKS_DIR;
            case "demo_matrix" -> MAKECODE_DIR;
            case "demo_snake", "demo_dodge", "demo_effects", "demo_pong" -> PYTHON_DIR;
            case "demo_lofi_control" -> CARDBOARD_CONTROL_DIR;
            case "demo_lofi_face" -> CARDBOARD_FACE_DIR;
            default -> CUSTOM_DIR;
        };
    }

    private String getFileExtension(String fileName) {
        if (fileName.equals("one_time_pairing")) {
            return ".hex";
        } else if (fileName.startsWith("demo_")) {
            return ".hex";
        } else if (fileName.equals("blocks")) {
            return ".hex";
        }
        return "";
    }

    private int getRawResourceId(String fileName) {
        try {
            return getResources().getIdentifier(fileName, "raw", getPackageName());
        } catch (Exception e) {
            Log.e("App", "Error getting resource ID for: " + fileName, e);
            return 0;
        }
    }
}