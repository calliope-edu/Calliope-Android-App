package cc.calliope.mini.utils;

import android.content.Context;
import android.util.Log;
import android.webkit.URLUtil;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
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

}
