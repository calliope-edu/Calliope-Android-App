package cc.calliope.mini.fragment.settings;

import android.os.Bundle;

import androidx.preference.PreferenceFragmentCompat;
import cc.calliope.mini.R;

public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }
}