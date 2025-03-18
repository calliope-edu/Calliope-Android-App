package cc.calliope.mini.utils.settings;

import android.content.Context;
import androidx.preference.PreferenceManager;

public class Preference {
    public static String getString(Context context, String key, String defValue) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(key, defValue);
    }

    public static void putString(Context context, String key, String value) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(key, value).apply();
    }

    public static int getInt(Context context, String key, int defValue){
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(key, defValue);
    }

    public static void putInt(Context context, String key, int value){
        PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(key, value).apply();
    }

    public static boolean getBoolean(Context context, String key, Boolean defValue){
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, defValue);
    }

    public static void putBoolean(Context context, String key, Boolean value){
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(key, value).apply();
    }
}
