package cc.calliope.mini.ui.fragment.settings;

import android.os.Bundle;
import android.text.Html;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;

import cc.calliope.mini.R;
import cc.calliope.mini.ui.SnackbarHelper;
import cc.calliope.mini.ui.dialog.DialogUtils;
import cc.calliope.mini.utils.bluetooth.ConnectedDevicesManager;

public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("pref_key_contact".equals(preference.getKey())) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                preference.setSummary(Html.fromHtml(getString(R.string.text_contact), Html.FROM_HTML_MODE_LEGACY));
            } else {
                preference.setSummary(Html.fromHtml(getString(R.string.text_contact)));
            }
            preference.setSelectable(false);
        } else if ("pref_key_help".equals(preference.getKey())) {
            NavController navController = Navigation.findNavController(requireActivity(), R.id.navigation_host_fragment);
            navController.navigate(R.id.action_settings_to_help);
            return true;
        } else if ("pref_key_remove_all_devices".equals(preference.getKey())) {
            removeAllDevices();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    // TODO: показувати список підключених пристроїв, патернів?
    private void removeAllDevices() {
        ConnectedDevicesManager manager = new ConnectedDevicesManager(requireContext());
        List<String> devices = manager.getConnectedAddresses();
        int count = devices.size();

        if (count == 0) {
            SnackbarHelper.warningSnackbar(requireView(), getString(R.string.snackbar_no_calliope_connected)).show();
            return;
        }

        String title = getString(R.string.title_dialog_delete_all_devices);
        String message = String.format(getString(R.string.info_dialog_delete_all_devices), count);

        DialogUtils.showWarningDialog(requireActivity(), title, message, () -> {
            manager.removeAllDevices();
            SnackbarHelper.infoSnackbar(requireView(), getString(R.string.snackbar_calliope_removed)).show();
        });
    }
}
