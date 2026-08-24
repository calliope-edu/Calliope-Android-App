package cc.calliope.mini;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;


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

    private void copyFilesToInternalStorage() {
        for (String fileName : RAW_FILES) {
            String targetDir = getTargetDirectory(fileName);
            File libraryDir = new File(getFilesDir(), targetDir);
            if (!libraryDir.exists()) {
                libraryDir.mkdirs();
            }
            copyRawFileToInternalStorage(libraryDir, fileName);
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

    private void copyRawFileToInternalStorage(File libraryDir, String fileName) {
        String extension = getFileExtension(fileName);
        String fullFileName = fileName + extension;

        File file = new File(libraryDir, fullFileName);
        if (!file.exists()) {
            try {
                int resourceId = getRawResourceId(fileName);
                if (resourceId != 0) {
                    try (InputStream inputStream = getResources().openRawResource(resourceId);
                         FileOutputStream outputStream = new FileOutputStream(file)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                        Log.d("App", "Successfully copied " + fullFileName + " to internal storage");
                    }
                } else {
                    Log.w("App", "Resource not found for file: " + fileName);
                }
            } catch (IOException e) {
                Log.e("App", "Error copying file: " + fullFileName, e);
            }
        } else {
            Log.d("App", "File already exists: " + fullFileName);
        }
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