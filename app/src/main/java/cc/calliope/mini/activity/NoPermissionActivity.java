package cc.calliope.mini.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityNoPermissionBinding;
import cc.calliope.mini.utils.Permission;
import cc.calliope.mini.utils.Version;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

public class NoPermissionActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int REQUEST_CODE = 1022; // random number
    private NoPermissionContent content;
    private ActivityNoPermissionBinding binding;
    private boolean deniedForever;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityNoPermissionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.actionButton.setOnClickListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!Permission.isAccessGranted(this, Permission.BLUETOOTH_PERMISSIONS)) {
            deniedForever = Permission.isAccessDeniedForever(this, Permission.BLUETOOTH_PERMISSIONS);
            content = NoPermissionContent.BLUETOOTH;
            updateUi();
        } else if (!Version.VERSION_S_AND_NEWER && !Permission.isAccessGranted(this, Permission.LOCATION_PERMISSIONS)) {
            deniedForever = Permission.isAccessDeniedForever(this, Permission.LOCATION_PERMISSIONS);
            content = NoPermissionContent.LOCATION;
            updateUi();
        } else if (Version.VERSION_TIRAMISU_AND_NEWER && !Permission.isAccessGranted(this, Permission.POST_NOTIFICATIONS)) {
            deniedForever = Permission.isAccessDeniedForever(this, Permission.LOCATION_PERMISSIONS);
            content = NoPermissionContent.NOTIFICATIONS;
            updateUi();
        } else {
            finish();
        }
    }

    @Override
    public void onClick(View v) {
        if (deniedForever) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        } else {
            Permission.markPermissionRequested(this, content.getPermissionsArray());
            ActivityCompat.requestPermissions(this, content.getPermissionsArray(), REQUEST_CODE);
        }
    }

    private void updateUi() {
        binding.iconImageView.setImageResource(content.getIcResId());
        binding.titleTextView.setText(content.getTitleResId());
        binding.messageTextView.setText(content.getMessageResId());
        binding.actionButton.setText(deniedForever ? R.string.settings_btn_permission : R.string.action_btn_permission);
    }
}