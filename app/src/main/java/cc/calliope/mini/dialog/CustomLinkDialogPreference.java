package cc.calliope.mini.dialog;

import android.content.Context;
import android.util.AttributeSet;
import androidx.preference.DialogPreference;
import cc.calliope.mini.fragment.editors.Editor;
import cc.calliope.mini.utils.Preference;

import static cc.calliope.mini.utils.Preference.PREF_KEY_CUSTOM_LINK;

public class CustomLinkDialogPreference extends DialogPreference {
    public CustomLinkDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        String dialogTitle = getDialogTitle() == null ? "" : getDialogTitle().toString();
        String link = Preference.getString(getContext(), PREF_KEY_CUSTOM_LINK, Editor.CUSTOM.getUrl());
        DialogUtils.showEditDialog(getContext(), dialogTitle, link, output -> {
                    if (shouldPersist()) {
                        persistString(output);
                    }
                }
        );
    }
}