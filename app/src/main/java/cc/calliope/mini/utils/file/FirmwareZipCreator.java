package cc.calliope.mini.utils.file;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FirmwareZipCreator {
    private static final String TAG = "FirmwareZipCreator";

    private final Context context;
    private final String firmwarePath;
    private final String initPacketPath;

    public FirmwareZipCreator(Context context, String firmwarePath, String initPacketPath) {
        this.context = context;
        this.firmwarePath = firmwarePath;
        this.initPacketPath = initPacketPath;
    }

    public String createZip() {
        String zipFilePath = context.getCacheDir() + "/update.zip";

        File zipFile = initializeZipFile(zipFilePath);
        if (zipFile == null) {
            return null;
        }

        if (!addFilesToZip(zipFile, firmwarePath, initPacketPath)) {
            return null;
        }

        return zipFilePath;
    }

    private File initializeZipFile(String path) {
        File zipFile = new File(path);
        try {
            if (zipFile.exists() && !zipFile.delete()) {
                Log.e(TAG, "Failed to delete existing file: " + path);
                return null;
            }

            if (!zipFile.createNewFile()) {
                Log.e(TAG, "Failed to create new file: " + path);
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error initializing zip file", e);
            return null;
        }

        return zipFile;
    }

    private boolean addFilesToZip(File zipFile, String... srcFiles) {
        byte[] buffer = new byte[1024];

        try (FileOutputStream fileOutputStream = new FileOutputStream(zipFile);
             ZipOutputStream zipOutputStream = new ZipOutputStream(fileOutputStream)) {

            for (String file : srcFiles) {
                File srcFile = new File(file);

                if (!srcFile.exists()) {
                    Log.e(TAG, "Source file does not exist: " + file);
                    continue;
                }

                try (FileInputStream fileInputStream = new FileInputStream(srcFile)) {
                    zipOutputStream.putNextEntry(new ZipEntry(srcFile.getName()));

                    int length;
                    while ((length = fileInputStream.read(buffer)) > 0) {
                        zipOutputStream.write(buffer, 0, length);
                    }

                    zipOutputStream.closeEntry();
                }
            }

            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error adding files to zip", e);
            return false;
        }
    }
}