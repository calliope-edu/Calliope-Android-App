package cc.calliope.mini.utils.file;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.URLUtil;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cc.calliope.mini.utils.settings.Settings;

public class FileUtils {
    private static final String TAG = "FileUtils";
    private static final String FILE_EXTENSION = ".hex";

    /**
     * File name without its last extension ("script.hex" -> "script").
     * The dot must come after the last path separator to count.
     */
    public static String removeExtension(String name) {
        int dot = name.lastIndexOf('.');
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return dot > separator ? name.substring(0, dot) : name;
    }

    /**
     * Last path segment without its extension ("/a/b/script.hex" -> "script").
     */
    public static String getBaseName(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return removeExtension(path.substring(separator + 1));
    }

    /**
     * Version-detection cache. {@link #getFileVersion} can scan an entire
     * hex file (see containsV3Evidence) and is called per row while binding
     * the scripts list, so re-scanning the same file on every rebind caused
     * scroll jank. Keyed by path + lastModified + length so a rewritten file
     * (same path, new content) misses and is re-scanned.
     */
    private static final Map<String, FileVersion> VERSION_CACHE = new ConcurrentHashMap<>();
    private static final int VERSION_CACHE_MAX = 256;

    public static File getFile(Context context, String editorName, String filename) {

        File dir = new File(context.getFilesDir().toString() + File.separator + editorName);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        Log.w(TAG, "DIR: " + dir);

        File file = new File(dir.getAbsolutePath() + File.separator + filename + FILE_EXTENSION);

        if (!Settings.isRenameFiles(context) && file.exists()) {
            file.delete();
        } else {
            int i = 1;
            while (file.exists()) {
                String number = String.format("(%s)", ++i);
                file = new File(dir.getAbsolutePath() + File.separator + filename + number + FILE_EXTENSION);
            }
        }

        try {
            if (file.createNewFile()) {
                Log.w(TAG, "createNewFile: " + file);
                return file;
            } else {
                Log.e(TAG, "CreateFile Error, deleting: " + file);
                if (!file.delete()) {
                    Log.e(TAG, "Delete Error, deleting: " + file);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getFileName(String url) {
        int start = url.indexOf("data:");
        int end = url.indexOf(".hex;");
        String name;

        if (start != -1 && end != -1) {
            name = url.substring(start, end); //this will give abc
            name = name.replace("data:", "");
            name = name.replace("mini-", "");
            return name;
        } else if (URLUtil.isValidUrl(url) && url.endsWith(".hex")) {
            return getBaseName(url);
        } else {
            return "firmware";
        }
    }

    public static String getFileSize(String str) {
        File file = new File(str);
        return file.exists() ? Long.toString(file.length()) : "0";
    }

    public static FileVersion getFileVersion(String filePath) {
        File file = new File(filePath);
        String cacheKey = filePath + ":" + file.lastModified() + ":" + file.length();
        FileVersion cached = VERSION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        FileVersion result = computeFileVersion(filePath);

        // The set of distinct hex files is small; a hard clear is enough to
        // keep the cache from growing without bound.
        if (VERSION_CACHE.size() > VERSION_CACHE_MAX) {
            VERSION_CACHE.clear();
        }
        VERSION_CACHE.put(cacheKey, result);
        return result;
    }

    private static FileVersion computeFileVersion(String filePath) {
        String[] lines = new String[2];
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            lines[0] = br.readLine();
            lines[1] = br.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return FileVersion.UNDEFINED;
        }

        FileVersion shallow = FileVersion.UNDEFINED;
        for (FileVersion fv : FileVersion.values()) {
            if (fv == FileVersion.UNDEFINED) {
                continue;
            }
            int lineIndex = fv.getLineNumber() - 1;
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                if (line != null && line.startsWith(fv.getPattern())) {
                    shallow = fv;
                    break;
                }
            }
        }

        // The shallow check tags every hex starting with ":020000040000FA"
        // (an Extended Linear Address 0x0000 record — present at line 1 of
        // basically every Intel HEX file) as VERSION_2. That misclassifies
        // universal hexes that lack the Microsoft "C0DE" line-2 marker,
        // e.g. MicroPython on V3 — they get rejected as V1/V2-only on V3
        // boards.
        //
        // We can confirm V3-compatibility without trusting line 1 by
        // scanning the records for either:
        //   - a data record at an address >= 0x40000 (nRF51 / V1+V2 only
        //     has 256 KB flash, so anything addressed >= 0x40000 must be
        //     V3-targeted), OR
        //   - any Microsoft-Universal-Hex block-marker record types
        //     (0x0A–0x0E), which only appear in genuine universal hexes.
        //
        // Reclassify VERSION_2 → UNIVERSAL when either signal fires.
        // Leave VERSION_3 and UNIVERSAL alone (the shallow match is
        // already definitive for those).
        if (shallow == FileVersion.VERSION_2 && containsV3Evidence(filePath)) {
            return FileVersion.UNIVERSAL;
        }

        return shallow;
    }

    /**
     * Scan a hex file for irrefutable V3-compatibility evidence. Returns
     * true on the first hit; bails out of the loop early. Worst-case
     * O(file size) but only invoked for V2-classified files where the
     * deeper check is needed.
     */
    private static boolean containsV3Evidence(String filePath) {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            long baseAddr = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 11 || line.charAt(0) != ':') continue;
                // Intel HEX:  :LLAAAATT...CC
                //   LL = byte count (2), AAAA = address (4), TT = type (2)
                int recordType;
                try {
                    recordType = Integer.parseInt(line.substring(7, 9), 16);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (recordType == 0x04) {
                    // Extended Linear Address — updates the upper 16 bits
                    // of subsequent data record addresses.
                    if (line.length() >= 13) {
                        try {
                            int upperAddr = Integer.parseInt(line.substring(9, 13), 16);
                            baseAddr = ((long) upperAddr) << 16;
                        } catch (NumberFormatException ignored) { /* malformed */ }
                    }
                } else if (recordType == 0x00) {
                    // Data record — combine with baseAddr to get the full
                    // 32-bit destination address.
                    try {
                        int loAddr = Integer.parseInt(line.substring(3, 7), 16);
                        long fullAddr = baseAddr + loAddr;
                        if (fullAddr >= 0x40000L) {
                            return true;  // V3-only flash region
                        }
                    } catch (NumberFormatException ignored) { /* malformed */ }
                } else if (recordType >= 0x0A && recordType <= 0x0E) {
                    // Microsoft Universal-Hex block-marker records — only
                    // present in genuine universal hexes (irrespective of
                    // whether the "C0DE" line-2 marker is also there).
                    return true;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "containsV3Evidence: scan failed", e);
        }
        return false;
    }

    public static boolean writeFile(String path, byte[] data)  {
        File file = new File(path);

        if (file.exists() && !file.delete()) {
            Log.e("FileUtils", "Failed to delete existing file: " + path);
            return false;
        }

        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            if (file.createNewFile()) {
                Log.w("FileUtils", "The named file already exists: " + path);
            }
            outputStream.write(data);
            outputStream.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Camera file methods
    public static File createImageFile(Context context) {
        try {
            String timeStamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            String imageFileName = "JPEG_" + timeStamp + "_";
            File storageDir = context.getCacheDir();
            return File.createTempFile(imageFileName, ".jpg", storageDir);
        } catch (IOException e) {
            Log.e(TAG, "Error creating image file", e);
            return null;
        }
    }

    public static Uri getUriForFile(Context context, File file) {
        return FileProvider.getUriForFile(context, "cc.calliope.file_provider", file);
    }
}
