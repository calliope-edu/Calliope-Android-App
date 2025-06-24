package cc.calliope.mini.ui.activity;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import cc.calliope.mini.R;
import cc.calliope.mini.ui.fragment.settings.SettingsFragment;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, new SettingsFragment())
                .commit();
    }
}