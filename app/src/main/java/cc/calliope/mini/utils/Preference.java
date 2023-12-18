package cc.calliope.mini.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public class Preference {
    public static final String PREF_KEY_ENABLE_AUTO_FLASHING = "pref_key_enable_auto_flashing";
    public static final String PREF_KEY_RENAME_FILES = "pref_key_rename_files";
    public static final String PREF_KEY_ENABLE_PARTIAL_FLASHING = "pref_key_enable_partial_flashing";
    public static final String PREF_KEY_CUSTOM_LINK = "pref_key_custom_link";


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
}
