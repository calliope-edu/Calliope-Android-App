package cc.calliope.mini.ui.fragment.settings;

import android.os.Bundle;
import android.text.Html;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import cc.calliope.mini.R;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("pref_key_contact".equals(preference.getKey())) {
            // Set HTML-formatted text for API Level 23 and above
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                preference.setSummary(Html.fromHtml(getString(R.string.text_contact), Html.FROM_HTML_MODE_LEGACY));
            } else {
                // For API Level below 24
                preference.setSummary(Html.fromHtml(getString(R.string.text_contact)));
            }
            preference.setSelectable(false);
        } else if ("pref_key_help".equals(preference.getKey())) {
            NavController navController = Navigation.findNavController(requireActivity(), R.id.navigation_host_fragment);
            navController.navigate(R.id.action_settings_to_help);
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }
}