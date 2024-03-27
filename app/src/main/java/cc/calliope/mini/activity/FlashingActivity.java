package cc.calliope.mini.activity;


import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import cc.calliope.mini.FlashingService;
import cc.calliope.mini.R;
import cc.calliope.mini.databinding.ActivityDfuBinding;
import cc.calliope.mini.utils.Preference;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.views.BoardProgressBar;
import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

public class FlashingActivity extends AppCompatActivity {
    private static final int DELAY_TO_FINISH_ACTIVITY = 5000; // delay to finish activity after flashing
    private ActivityDfuBinding binding;
    private TextView title;
    private TextView status;
    private BoardProgressBar progressBar;
    private final Handler timerHandler = new Handler();
    private final Runnable deferredFinish = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityDfuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        status = binding.statusTextView;
        title = binding.titleTextView;
        progressBar = binding.progressBar;

        binding.retryButton.setOnClickListener(this::onRetryClicked);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        DfuServiceListenerHelper.registerProgressListener(this, dfuProgressListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        DfuServiceListenerHelper.unregisterProgressListener(this, dfuProgressListener);
    }

    private final DfuProgressListener dfuProgressListener = new DfuProgressListenerAdapter() {
        @Override
        public void onDeviceConnecting(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_device_connecting);
        }

        @Override
        public void onDeviceConnected(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_device_connected);
        }

        @Override
        public void onDfuProcessStarting(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_process_starting);
        }

        @Override
        public void onDfuProcessStarted(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_process_started);
        }

        @Override
        public void onEnablingDfuMode(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_enabling_dfu_mode);
        }

        @Override
        public void onProgressChanged(@NonNull String deviceAddress, int percent, float speed, float avgSpeed, int currentPart, int partsTotal) {
            status.setText(R.string.flashing_uploading);
            progressBar.setProgress(percent);
            title.setText(String.format(getString(R.string.flashing_percent), percent));
        }

        @Override
        public void onFirmwareValidating(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_firmware_validating);
        }

        @Override
        public void onDeviceDisconnecting(String deviceAddress) {
            status.setText(R.string.flashing_device_disconnecting);
        }

        @Override
        public void onDeviceDisconnected(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_device_disconnected);
        }

        @Override
        public void onDfuCompleted(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_completed);
            finishActivity();
        }

        @Override
        public void onDfuAborted(@NonNull String deviceAddress) {
            status.setText(R.string.flashing_aborted);
        }

        @Override
        public void onError(@NonNull String deviceAddress, int error, int errorType, String message) {
            if (error == 4110) {
                return;
            }
            progressBar.setProgress(0);
            binding.retryButton.setVisibility(View.VISIBLE);
            status.setText(String.format(getString(R.string.flashing_error), error, message));
        }
    };

    private void onRetryClicked(View view) {
        view.setVisibility(View.INVISIBLE);
        Intent serviceIntent = new Intent(this, FlashingService.class);
        serviceIntent.putExtra(Constants.EXTRA_FILE_PATH, Preference.getString(this, Constants.CURRENT_FILE_PATH, ""));
        startService(serviceIntent);
    }

    private void finishActivity() {
        timerHandler.postDelayed(deferredFinish, DELAY_TO_FINISH_ACTIVITY);
    }
}