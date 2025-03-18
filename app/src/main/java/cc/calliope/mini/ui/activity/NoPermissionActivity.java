package cc.calliope.mini.ui.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityNoPermissionBinding;
import cc.calliope.mini.utils.Permission;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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
       checkPermissionsAndUpdateUI();
    }

    private void checkPermissionsAndUpdateUI() {
        if (!Permission.isAccessGranted(this, Permission.BLUETOOTH_PERMISSIONS)) {
            prepareUIUpdate(Permission.BLUETOOTH_PERMISSIONS, NoPermissionContent.BLUETOOTH);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !Permission.isAccessGranted(this, Permission.LOCATION_PERMISSIONS)) {
            prepareUIUpdate(Permission.LOCATION_PERMISSIONS, NoPermissionContent.LOCATION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permission.isAccessGranted(this, Permission.POST_NOTIFICATIONS)) {
            prepareUIUpdate(Permission.POST_NOTIFICATIONS, NoPermissionContent.NOTIFICATIONS);
        } else {
            finish();
        }
    }

    private void prepareUIUpdate(String[] permissions, NoPermissionContent content) {
        deniedForever = Permission.isAccessDeniedForever(this, permissions);
        this.content = content;
        updateUi();
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