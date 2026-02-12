package cc.calliope.mini.ui.activity;

import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.State.STATE_ERROR;
import static cc.calliope.mini.core.state.State.STATE_FLASHING;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;

import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import cc.calliope.mini.core.service.DfuService;
import cc.calliope.mini.core.service.FlashingService;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Event;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.Progress;
import cc.calliope.mini.core.state.State;
import cc.calliope.mini.databinding.ActivityDfuBinding;
import cc.calliope.mini.ui.views.BoardProgressBar;
import cc.calliope.mini.core.state.Error;

public class FlashingActivity extends AppCompatActivity {
    private static final String TAG = "FlashingActivity";
    private static final int DELAY_TO_FINISH_ACTIVITY = 3000; // delay to finish activity after flashing
    private ActivityDfuBinding binding;
    private TextView title;
    private TextView status;
    private BoardProgressBar progressBar;
    private final Handler timerHandler = new Handler();
    private final Runnable deferredFinish = () -> {
        Log.d(TAG, "deferredFinish: executing finish(), this=" + this.hashCode());
        finish();
    };
    private boolean flashingCompleted = false;
    private boolean flashingStarted = false; // Track if real flashing has started

    private final Observer<Event<Notification>> notificationObserver = event -> {
        Notification notification = event.getContentIfNotHandled();
        Log.d(TAG, "notificationObserver: event=" + event + ", notification=" + notification + ", handled=" + (notification == null));
        if (notification == null) return;

        Log.d(TAG, "notificationObserver: setting status to: " + notification.getMessage());
        status.setText(notification.getMessage());
    };

    private final Observer<Progress> progressObserver = new Observer<>() {
        @Override
        public void onChanged(Progress progress) {
            Log.d(TAG, "progressObserver: progress=" + progress);
            if (progress == null) {
                return;
            }

            int percent = progress.getValue();
            Log.d(TAG, "progressObserver: percent=" + percent + ", flashingCompleted=" + flashingCompleted + ", flashingStarted=" + flashingStarted);

            // Mark flashing as started when we see real progress (0-100)
            if (percent >= 0 && percent <= 100) {
                flashingStarted = true;
            }

            switch (percent) {
                case DfuService.PROGRESS_COMPLETED:
                    // Ignore stale PROGRESS_COMPLETED from previous session
                    if (!flashingStarted) {
                        Log.d(TAG, "progressObserver: ignoring stale PROGRESS_COMPLETED (flashing not started yet)");
                        return;
                    }
                    Log.d(TAG, "progressObserver: PROGRESS_COMPLETED, calling finishActivity()");
                    status.setText(R.string.flashing_completed);
                    finishActivity();
                    break;
                case DfuService.PROGRESS_CONNECTING:
                    Log.d(TAG, "progressObserver: PROGRESS_CONNECTING");
                    status.setText(R.string.flashing_device_connecting);
                    break;
                case DfuService.PROGRESS_STARTING:
                    Log.d(TAG, "progressObserver: PROGRESS_STARTING");
                    status.setText(R.string.flashing_process_starting);
                    break;
                case DfuService.PROGRESS_ENABLING_DFU_MODE:
                    Log.d(TAG, "progressObserver: PROGRESS_ENABLING_DFU_MODE");
                    status.setText(R.string.flashing_enabling_dfu_mode);
                    break;
                case DfuService.PROGRESS_VALIDATING:
                    Log.d(TAG, "progressObserver: PROGRESS_VALIDATING");
                    status.setText(R.string.flashing_firmware_validating);
                    break;
                case DfuService.PROGRESS_DISCONNECTING:
                    Log.d(TAG, "progressObserver: PROGRESS_DISCONNECTING");
                    status.setText(R.string.flashing_device_disconnecting);
                    break;
                case DfuService.PROGRESS_ABORTED:
                    Log.d(TAG, "progressObserver: PROGRESS_ABORTED");
                    status.setText(R.string.flashing_aborted);
                    break;
                default:
                    if (percent >= 0 && percent <= 100) {
                        Log.d(TAG, "progressObserver: uploading percent=" + percent);
                        status.setText(R.string.flashing_uploading);
                        title.setText(String.format(getString(R.string.flashing_percent), percent));
                    } else {
                        Log.d(TAG, "progressObserver: unknown percent=" + percent);
                    }
                    break;
            }
            Log.d(TAG, "progressObserver: setting progressBar to " + percent);
            progressBar.setProgress(percent);
        }
    };

    private final Observer<State> stateObserver = state -> {
        Log.d(TAG, "stateObserver: state=" + state);
        if (state == null) {
            return;
        }

        Log.d(TAG, "stateObserver: stateType=" + state.getType());
        if (state.getType() == STATE_ERROR) {
            Error error = ApplicationStateHandler.getErrorLiveData().getValue();
            Log.d(TAG, "stateObserver: STATE_ERROR, error=" + error);
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
        Log.d(TAG, "onCreate: savedInstanceState=" + savedInstanceState + ", this=" + this.hashCode());

        binding = ActivityDfuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        status = binding.statusTextView;
        title = binding.titleTextView;
        progressBar = binding.progressBar;

        flashingCompleted = false;
        flashingStarted = false;
        Log.d(TAG, "onCreate: flashingCompleted and flashingStarted reset to false");

        ApplicationStateHandler.updateState(STATE_FLASHING);
        ApplicationStateHandler.getNotificationLiveData().observe(this, notificationObserver);
        ApplicationStateHandler.getProgressLiveData().observe(this, progressObserver);
        ApplicationStateHandler.getStateLiveData().observe(this, stateObserver);

        binding.retryButton.setOnClickListener(this::onRetryClicked);
        Log.d(TAG, "onCreate: observers registered");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: this=" + this.hashCode() + ", flashingCompleted=" + flashingCompleted);
        ApplicationStateHandler.getNotificationLiveData().removeObserver(notificationObserver);
        ApplicationStateHandler.getProgressLiveData().removeObserver(progressObserver);
        ApplicationStateHandler.getStateLiveData().removeObserver(stateObserver);
        binding = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: this=" + this.hashCode() + ", flashingCompleted=" + flashingCompleted);
        //DfuServiceListenerHelper.registerProgressListener(this, dfuProgressListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: this=" + this.hashCode() + ", flashingCompleted=" + flashingCompleted);
        //DfuServiceListenerHelper.unregisterProgressListener(this, dfuProgressListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop: this=" + this.hashCode() + ", flashingCompleted=" + flashingCompleted);

        // If flashing completed and activity goes to background, finish immediately
        // User can't see the success message anyway, so no point keeping it open
        if (flashingCompleted) {
            Log.d(TAG, "onStop: flashingCompleted=true, finishing immediately");
            timerHandler.removeCallbacks(deferredFinish);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "onBackPressed: this=" + this.hashCode());
        super.onBackPressed();
        finish();
    }

    private void onRetryClicked(View view) {
        Log.d(TAG, "onRetryClicked: this=" + this.hashCode());
        if (!Boolean.TRUE.equals(ApplicationStateHandler.getDeviceAvailabilityLiveData().getValue())) {
            ApplicationStateHandler.updateNotification(ERROR, R.string.error_no_connected);
            return;
        }

        view.setVisibility(View.INVISIBLE);
        flashingStarted = false;
        flashingCompleted = false;
        progressBar.setProgress(0);
        title.setText("");
        status.setText(R.string.flashing_process_starting);

        // Reset state before starting FlashingService, otherwise it will
        // observe STATE_ERROR and immediately call stopSelf()
        ApplicationStateHandler.updateState(STATE_FLASHING);

        Intent serviceIntent = new Intent(this, FlashingService.class);
        serviceIntent.putExtra(FlashingService.EXTRA_FORCE_FULL_DFU, true);
        startService(serviceIntent);
    }

    private void finishActivity() {
        Log.d(TAG, "finishActivity: this=" + this.hashCode() + ", setting flashingCompleted=true");
        flashingCompleted = true;
        timerHandler.postDelayed(deferredFinish, DELAY_TO_FINISH_ACTIVITY);
        Log.d(TAG, "finishActivity: timer scheduled for " + DELAY_TO_FINISH_ACTIVITY + "ms");
    }
}