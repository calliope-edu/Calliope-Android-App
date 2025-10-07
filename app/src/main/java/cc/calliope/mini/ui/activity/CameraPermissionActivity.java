package cc.calliope.mini.ui.activity;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityNoPermissionBinding;
import cc.calliope.mini.utils.Permission;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

public class CameraPermissionActivity extends AppCompatActivity implements View.OnClickListener {
    private static final int REQUEST_CODE = 1023; // random number
    private ActivityNoPermissionBinding binding;
    private boolean deniedForever;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityNoPermissionBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.actionButton.setOnClickListener(this);
        
        // Set up UI for camera permission
        setupCameraPermissionUI();
    }

    private void setupCameraPermissionUI() {
        deniedForever = Permission.isAccessDeniedForever(this, Permission.CAMERA_PERMISSIONS);
        
        binding.iconImageView.setImageResource(NoPermissionContent.CAMERA.getIcResId());
        binding.titleTextView.setText(NoPermissionContent.CAMERA.getTitleResId());
        binding.messageTextView.setText(NoPermissionContent.CAMERA.getMessageResId());
        binding.actionButton.setText(deniedForever ? R.string.settings_btn_permission : R.string.action_btn_permission);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Check if permission was granted while in settings
        if (Permission.isAccessGranted(this, Permission.CAMERA_PERMISSIONS)) {
            // Permission granted, return to previous activity
            setResult(RESULT_OK);
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
            Permission.markPermissionRequested(this, Permission.CAMERA_PERMISSIONS);
            ActivityCompat.requestPermissions(this, Permission.CAMERA_PERMISSIONS, REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE) {
            if (Permission.isAccessGranted(this, Permission.CAMERA_PERMISSIONS)) {
                // Permission granted
                setResult(RESULT_OK);
                finish();
            } else {
                // Permission denied, update UI
                setupCameraPermissionUI();
            }
        }
    }
}
