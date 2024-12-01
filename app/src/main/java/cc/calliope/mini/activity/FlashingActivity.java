package cc.calliope.mini.activity;

import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.State.STATE_ERROR;
import static cc.calliope.mini.core.state.State.STATE_FLASHING;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import cc.calliope.mini.core.service.DfuService;
import cc.calliope.mini.core.service.FlashingService;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.databinding.ActivityDfuBinding;
import cc.calliope.mini.utils.Preference;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.views.BoardProgressBar;
import cc.calliope.mini.core.state.Error;

public class FlashingActivity extends AppCompatActivity {
    private static final int DELAY_TO_FINISH_ACTIVITY = 5000; // delay to finish activity after flashing
    private ActivityDfuBinding binding;
    private TextView title;
    private TextView status;
    private BoardProgressBar progressBar;
    private final Handler timerHandler = new Handler();
    private final Runnable deferredFinish = this::finish;

    private final Observer<Notification> notificationObserver = new Observer<>() {
        @Override
        public void onChanged(Notification notification) {
            status.setText(notification.getMessage());
        }
    };

    private final Observer<Progress> progressObserver = new Observer<>() {
        @Override
        public void onChanged(Progress progress) {
            if (progress == null) {
                return;
            }

            int percent = progress.getValue();

            switch (percent) {
                case DfuService.PROGRESS_COMPLETED:
                    status.setText(R.string.flashing_completed);
                    finishActivity();
                    break;
                case DfuService.PROGRESS_CONNECTING:
                    status.setText(R.string.flashing_device_connecting);
                    break;
                case DfuService.PROGRESS_STARTING:
                    status.setText(R.string.flashing_process_starting);
                    break;
                case DfuService.PROGRESS_ENABLING_DFU_MODE:
                    status.setText(R.string.flashing_enabling_dfu_mode);
                    break;
                case DfuService.PROGRESS_VALIDATING:
                    status.setText(R.string.flashing_firmware_validating);
                    break;
                case DfuService.PROGRESS_DISCONNECTING:
                    status.setText(R.string.flashing_device_disconnecting);
                    break;
                case DfuService.PROGRESS_ABORTED:
                    status.setText(R.string.flashing_aborted);
                    break;
                default:
                    if (percent >= 0 && percent <= 100) {
                        status.setText(R.string.flashing_uploading);
                        title.setText(String.format(getString(R.string.flashing_percent), percent));
                    }
                    break;
            }
            progressBar.setProgress(percent);
        }
    };

    private final Observer<State> stateObserver = state -> {
        if (state == null) {
            return;
        }

        if (state.getType() == STATE_ERROR) {
            Error error = ApplicationStateHandler.getErrorLiveData().getValue();
            if (error != null) {
                status.setText(String.format(getString(R.string.flashing_error), error.getCode(), error.getMessage()));
            }
            progressBar.setProgress(DfuService.PROGRESS_ABORTED);
            binding.retryButton.setVisibility(View.VISIBLE);
            //finishActivity();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityDfuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        status = binding.statusTextView;
        title = binding.titleTextView;
        progressBar = binding.progressBar;

        ApplicationStateHandler.updateState(STATE_FLASHING);
        ApplicationStateHandler.getNotificationLiveData().observe(this, notificationObserver);
        ApplicationStateHandler.getProgressLiveData().observe(this, progressObserver);
        ApplicationStateHandler.getStateLiveData().observe(this, stateObserver);

        binding.retryButton.setOnClickListener(this::onRetryClicked);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ApplicationStateHandler.getNotificationLiveData().removeObserver(notificationObserver);
        ApplicationStateHandler.getProgressLiveData().removeObserver(progressObserver);
        ApplicationStateHandler.getStateLiveData().removeObserver(stateObserver);
        binding = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        //DfuServiceListenerHelper.registerProgressListener(this, dfuProgressListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        //DfuServiceListenerHelper.unregisterProgressListener(this, dfuProgressListener);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void onRetryClicked(View view) {
        if (ApplicationStateHandler.getDeviceAvailabilityLiveData().getValue() == null || !ApplicationStateHandler.getDeviceAvailabilityLiveData().getValue()) {
            ApplicationStateHandler.updateNotification(ERROR, R.string.error_no_connected);
            return;
        }

        view.setVisibility(View.INVISIBLE);
        Intent serviceIntent = new Intent(this, FlashingService.class);
        startService(serviceIntent);
    }

    private void finishActivity() {
        timerHandler.postDelayed(deferredFinish, DELAY_TO_FINISH_ACTIVITY);
    }
}