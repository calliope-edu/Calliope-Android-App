package cc.calliope.mini.fragment.settings;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import cc.calliope.mini.R;

public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Preference contactPreference = findPreference("pref_key_contact");
        if (contactPreference != null) {
            // Set HTML-formatted text for API Level 23 and above
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                contactPreference.setSummary(Html.fromHtml(getString(R.string.summary_contact), Html.FROM_HTML_MODE_LEGACY));
            } else {
                // For API Level below 24
                contactPreference.setSummary(Html.fromHtml(getString(R.string.summary_contact)));
            }
            contactPreference.setSelectable(false);

            contactPreference.setOnPreferenceClickListener(preference -> true);
            contactPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (getView() != null) {
                    TextView summaryView = getView().findViewById(android.R.id.summary);
                    if (summaryView != null) {
                        summaryView.setMovementMethod(LinkMovementMethod.getInstance());
                    }
                }
                return true;
            });
        }
    }
}