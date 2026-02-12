package cc.calliope.mini;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import cc.calliope.mini.core.bluetooth.CheckService;

public class App extends Application {
    private static final String CUSTOM_DIR = "CUSTOM";
    private static final String MAKECODE_DIR = "MAKECODE";
    private static final String CARDBOARD_CONTROL_DIR = "CARDBOARD_CONTROL";
    private static final String CARDBOARD_FACE_DIR = "CARDBOARD_FACE";
    
    private static final String[] RAW_FILES = {
        "one_time_pairing",
        "demo_lofi_control", 
        "demo_lofi_face",
        "demo_snake",
        "demo_matrix"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AppContext.initialize(this);
        copyFilesToInternalStorage();

        // Start CheckService for device availability monitoring
        startService(new Intent(this, CheckService.class));
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
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
        if (fileName.equals("one_time_pairing")) {
            return CUSTOM_DIR;
        } else if (fileName.equals("demo_snake") || fileName.equals("demo_matrix")) {
            return MAKECODE_DIR;
        } else if (fileName.equals("demo_lofi_control")) {
            return CARDBOARD_CONTROL_DIR;
        } else if (fileName.equals("demo_lofi_face")) {
            return CARDBOARD_FACE_DIR;
        }
        return CUSTOM_DIR; // default fallback
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