package cc.calliope.mini.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import cc.calliope.mini.fragment.editors.Editor;

public class Preference {
    private static final String PREF_KEY_ENABLE_AUTO_FLASHING = "pref_key_enable_auto_flashing";
    private static final String PREF_KEY_RENAME_FILES = "pref_key_rename_files";
    private static final String PREF_KEY_ENABLE_PARTIAL_FLASHING = "pref_key_enable_partial_flashing";
    private static final String PREF_KEY_CUSTOM_LINK = "pref_key_custom_link";

    public static String getString(Context context, String key, String defValue) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPreferences.getString(key, defValue);
    }

    public static boolean getBoolean(Context context, String key, Boolean defValue){
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPreferences.getBoolean(key, defValue);
    }

    public static void setBoolean(Context context, String key, Boolean value){
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean(key, value).apply();
    }

    public static boolean isAutoFlashingEnable(Context context){
        return getBoolean(context, Preference.PREF_KEY_ENABLE_AUTO_FLASHING, false);
    }

    public static boolean isPartialFlashingEnable(Context context){
        return getBoolean(context, Preference.PREF_KEY_ENABLE_PARTIAL_FLASHING, false);
    }

    public static boolean isRenameFiles(Context context){
        return getBoolean(context, Preference.PREF_KEY_RENAME_FILES, false);
    }

    public static String getCustomLink(Context context){
        return getString(context, PREF_KEY_CUSTOM_LINK, Editor.CUSTOM.getUrl());
    }
}
