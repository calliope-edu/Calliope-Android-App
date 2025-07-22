package cc.calliope.mini.utils.settings;

import android.content.Context;

import cc.calliope.mini.ui.model.EditorType;

public class Settings extends Preference {
    private static final String PREF_KEY_ENABLE_AUTO_FLASHING = "pref_key_enable_auto_flashing";
    private static final String PREF_KEY_RENAME_FILES = "pref_key_rename_files";
    private static final String PREF_KEY_ENABLE_PARTIAL_FLASHING = "pref_key_enable_partial_flashing";
    private static final String PREF_KEY_CUSTOM_LINK = "pref_key_custom_link";
    private static final String PREF_KEY_ENABLE_BACKGROUND_FLASHING = "pref_key_enable_background_flashing";
    
    // Editor visibility preferences
    private static final String PREF_KEY_EDITOR_VISIBILITY_PREFIX = "pref_key_editor_visibility_";

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
        return getString(context, PREF_KEY_CUSTOM_LINK, EditorType.CUSTOM.getUrlV2());
    }

    public static void setCustomLink(Context context, String link){
        putString(context, PREF_KEY_CUSTOM_LINK, link);
    }
    
    /**
     * Check if an editor is visible
     * @param context Application context
     * @param editorId Editor ID (e.g., "makecode", "roberta", etc.)
     * @return true if editor is visible, false otherwise
     */
    public static boolean isEditorVisible(Context context, String editorId) {
        return getBoolean(context, PREF_KEY_EDITOR_VISIBILITY_PREFIX + editorId, true);
    }
    
    /**
     * Set editor visibility
     * @param context Application context
     * @param editorId Editor ID (e.g., "makecode", "roberta", etc.)
     * @param visible true to make editor visible, false to hide it
     */
    public static void setEditorVisible(Context context, String editorId, boolean visible) {
        putBoolean(context, PREF_KEY_EDITOR_VISIBILITY_PREFIX + editorId, visible);
    }
    
    /**
     * Get visibility key for a specific editor
     * @param editorId Editor ID
     * @return Preference key for editor visibility
     */
    public static String getEditorVisibilityKey(String editorId) {
        return PREF_KEY_EDITOR_VISIBILITY_PREFIX + editorId;
    }
}
