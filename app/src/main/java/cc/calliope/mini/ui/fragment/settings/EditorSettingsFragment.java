package cc.calliope.mini.ui.fragment.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.FragmentEditorSettingsBinding;
import cc.calliope.mini.utils.settings.Settings;

public class EditorSettingsFragment extends Fragment {
    private FragmentEditorSettingsBinding binding;
    
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentEditorSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Set up back button
        binding.topAppBar.setNavigationOnClickListener(v -> {
            Navigation.findNavController(v).navigateUp();
        });
        
        // Add the preference fragment
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.editor_settings_container, new EditorSettingsPreferenceFragment())
                    .commit();
        }
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
    
    // Inner class for preference fragment
    public static class EditorSettingsPreferenceFragment extends PreferenceFragmentCompat {
        
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.editor_preferences, rootKey);
            
            // Set up preference change listeners for editor visibility
            setupEditorVisibilityListeners();
        }
        
        private void setupEditorVisibilityListeners() {
            // Listen for changes in editor visibility preferences
            String[] editorIds = {"makecode", "roberta", "blocks", "python", "custom", "cardboard_control", "cardboard_face"};
            
            for (String editorId : editorIds) {
                String prefKey = Settings.getEditorVisibilityKey(editorId);
                Preference preference = findPreference(prefKey);
                if (preference != null) {
                    preference.setOnPreferenceChangeListener((pref, newValue) -> {
                        // The change will be automatically saved by the preference system
                        // We just need to notify that the menu should be refreshed
                        return true;
                    });
                }
            }
        }
    }
} 