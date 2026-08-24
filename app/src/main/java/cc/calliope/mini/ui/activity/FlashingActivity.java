package cc.calliope.mini.ui.activity;

import static cc.calliope.mini.core.state.Notification.ERROR;
import static cc.calliope.mini.core.state.State.STATE_ERROR;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import android.util.Log;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Consumer;

import cc.calliope.mini.core.service.DfuService;
import cc.calliope.mini.core.service.FlashingService;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.RepoObserve;
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
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable deferredFinish = () -> {
        Log.d(TAG, "deferredFinish: executing finish()");
        finish();
    };
    private boolean flashingCompleted = false;

    private final Consumer<Notification> notificationObserver = notification -> {
        status.setText(notification.getMessage());
    };

    private final Consumer<Progress> progressObserver = new Consumer<>() {
        @Override
        public void accept(Progress progress) {
            if (progress == null) {
                return;
            }

            int percent = progress.getValue();

            // The repository's progress flow has replay = 0, so a recreated
            // activity can no longer receive a stale PROGRESS_COMPLETED from
            // a previous flash — the old flashingStarted guard is gone.
            switch (percent) {
                case DfuService.PROGRESS_COMPLETED:
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
                        status.setText(R.string.flashing_uploading);
                        title.setText(String.format(getString(R.string.flashing_percent), percent));
                    } else {
                        Log.w(TAG, "unknown progress value: " + percent);
                    }
                    break;
            }
            progressBar.setProgress(percent);
        }
    };

    private final Consumer<State> stateObserver = state -> {
        if (state == null) {
            return;
        }

        if (state.getType() == STATE_ERROR) {
            Error error = AppStateRepository.getError().getValue();
            Log.d(TAG, "stateObserver: STATE_ERROR, error=" + error);
            if (error != null) {
                status.setText(String.format(getString(R.string.flashing_error), error.getCode(), error.getMessage()));
            }
            progressBar.setProgress(DfuService.PROGRESS_ABORTED);
            binding.retryButton.setVisibility(View.VISIBLE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate" + (savedInstanceState != null ? " (recreated)" : ""));

        binding = ActivityDfuBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        status = binding.statusTextView;
        title = binding.titleTextView;
        progressBar = binding.progressBar;

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                
                finish();
            }
        });

        flashingCompleted = false;

        RepoObserve.notifications(this, notificationObserver);
        RepoObserve.progress(this, progressObserver);
        RepoObserve.state(this, stateObserver);

        binding.retryButton.setOnClickListener(this::onRetryClicked);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: flashingCompleted=" + flashingCompleted);
        binding = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // If flashing completed and activity goes to background, finish immediately
        // User can't see the success message anyway, so no point keeping it open
        if (flashingCompleted) {
            Log.d(TAG, "backgrounded after completion, finishing immediately");
            timerHandler.removeCallbacks(deferredFinish);
            finish();
        }
    }


    private void onRetryClicked(View view) {
        Log.d(TAG, "retry requested");
        if (!Boolean.TRUE.equals(AppStateRepository.getDeviceAvailable().getValue())) {
            AppStateRepository.updateNotification(ERROR, R.string.error_no_connected);
            return;
        }

        view.setVisibility(View.INVISIBLE);
        flashingCompleted = false;
        progressBar.setProgress(0);
        title.setText("");
        status.setText(R.string.flashing_process_starting);

        // Retry repeats the flash with the user's configured mode (partial if
        // enabled, otherwise full DFU) — same as a normal flash. The previous
        // force-full-DFU was a workaround for the stopSelf race (fixed in
        // FlashingService) and for unreliable partial flashing bonding.
        Intent serviceIntent = new Intent(this, FlashingService.class);
        startService(serviceIntent);
    }

    private void finishActivity() {
        Log.d(TAG, "flashing completed, deferred finish in " + DELAY_TO_FINISH_ACTIVITY + "ms");
        flashingCompleted = true;
        timerHandler.postDelayed(deferredFinish, DELAY_TO_FINISH_ACTIVITY);
    }
}