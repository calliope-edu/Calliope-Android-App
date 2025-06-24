package cc.calliope.mini.ui.dialog;

import android.content.Context;
import android.util.AttributeSet;
import androidx.preference.DialogPreference;

import cc.calliope.mini.utils.settings.Settings;

public class CustomLinkDialogPreference extends DialogPreference {
    public CustomLinkDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        String dialogTitle = getDialogTitle() == null ? "" : getDialogTitle().toString();
        String link = Settings.getCustomLink(getContext());
        DialogUtils.showEditDialog(getContext(), dialogTitle, link, output -> {
                    if (shouldPersist()) {
                        persistString(output);
                    }
                }
        );
    }
}