package cc.calliope.mini.utils;

import android.content.Context;

import cc.calliope.mini.fragment.editors.Editor;

public class Settings extends Preference{
    private static final String PREF_KEY_ENABLE_AUTO_FLASHING = "pref_key_enable_auto_flashing";
    private static final String PREF_KEY_RENAME_FILES = "pref_key_rename_files";
    private static final String PREF_KEY_ENABLE_PARTIAL_FLASHING = "pref_key_enable_partial_flashing";
    private static final String PREF_KEY_CUSTOM_LINK = "pref_key_custom_link";
    private static final String PREF_KEY_ENABLE_BACKGROUND_FLASHING = "pref_key_enable_background_flashing";

    public static boolean isAutoFlashingEnable(Context context){
        return getBoolean(context, PREF_KEY_ENABLE_AUTO_FLASHING, true);
    }

    public static boolean isPartialFlashingEnable(Context context){
        return getBoolean(context, PREF_KEY_ENABLE_PARTIAL_FLASHING, false);
        //return false;
    }

    public static boolean isBackgroundFlashingEnable(Context context){
        return getBoolean(context, PREF_KEY_ENABLE_BACKGROUND_FLASHING, true);
    }

    public static boolean isRenameFiles(Context context){
        return getBoolean(context, PREF_KEY_RENAME_FILES, false);
    }

    public static String getCustomLink(Context context){
        return getString(context, PREF_KEY_CUSTOM_LINK, Editor.CUSTOM.getUrl_v2());
    }
}
