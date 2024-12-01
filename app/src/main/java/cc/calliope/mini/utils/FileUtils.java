package cc.calliope.mini.utils;

import android.content.Context;
import android.util.Log;
import android.webkit.URLUtil;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

public class FileUtils {
    private static final String TAG = "FileUtils";
    private static final String FILE_EXTENSION = ".hex";

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
            name = StringUtils.remove(name, "data:");
            name = StringUtils.remove(name, "mini-");
            return name;
        } else if (URLUtil.isValidUrl(url) && url.endsWith(".hex")) {
            return FilenameUtils.getBaseName(url);
        } else {
            return "firmware";
        }
    }

    public static String getFileSize(String str) {
        File file = new File(str);
        return file.exists() ? Long.toString(file.length()) : "0";
    }

    public static FileVersion getFileVersion(String filePath) {
        String[] lines = new String[2];
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            lines[0] = br.readLine();
            lines[1] = br.readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return FileVersion.UNDEFINED;
        }

        for (FileVersion fv : FileVersion.values()) {
            if (fv == FileVersion.UNDEFINED) {
                continue;
            }
            int lineIndex = fv.getLineNumber() - 1;
            if (lineIndex >= 0 && lineIndex < lines.length) {
                String line = lines[lineIndex];
                if (line != null && line.startsWith(fv.getPattern())) {
                    return fv;
                }
            }
        }

        return FileVersion.UNDEFINED;
    }

    public static boolean writeFile2(String path, byte[] data) {
        try {
            File hexToFlash = new File(path);
            if (hexToFlash.exists()) {
                hexToFlash.delete();
            }
            hexToFlash.createNewFile();

            FileOutputStream outputStream = new FileOutputStream(hexToFlash);
            outputStream.write(data);
            outputStream.flush();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
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
}
