package cc.calliope.mini.utils.file;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the firmware and its init packet into the zip Nordic DFU expects.
 * The entry names are the source file names and matter to the library
 * (application.bin / application.dat), so uniqueness comes from the
 * directory the caller provides, not from the file names.
 */
public class FirmwareZipCreator {
    private static final String TAG = "FirmwareZipCreator";

    private final File zipFile;
    private final String[] sourcePaths;

    public FirmwareZipCreator(File zipFile, String... sourcePaths) {
        this.zipFile = zipFile;
        this.sourcePaths = sourcePaths;
    }

    /** @return the zip's absolute path, or null on failure. */
    public String createZip() {
        byte[] buffer = new byte[8192];
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String path : sourcePaths) {
                File source = new File(path);
                if (!source.exists()) {
                    Log.e(TAG, "Source file does not exist: " + path);
                    return null;
                }
                try (FileInputStream in = new FileInputStream(source)) {
                    zip.putNextEntry(new ZipEntry(source.getName()));
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, length);
                    }
                    zip.closeEntry();
                }
            }
            return zipFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Error creating zip", e);
            return null;
        }
    }
}
