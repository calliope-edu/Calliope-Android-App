package cc.calliope.mini.ui.activity;

import static cc.calliope.mini.core.state.Notification.ERROR;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Consumer;

import cc.calliope.mini.R;
import cc.calliope.mini.core.service.FlashingService;
import cc.calliope.mini.core.state.AppMode;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.FlashEvent;
import cc.calliope.mini.core.state.FlashMode;
import cc.calliope.mini.core.state.FlashResult;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.core.state.RepoObserve;
import cc.calliope.mini.databinding.ActivityDfuBinding;
import cc.calliope.mini.ui.views.BoardProgressBar;

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

    // Replay = 0: a recreated activity cannot receive a stale Done from a
    // previous flash. The sticky mode below covers what it needs to restore.
    private final Consumer<FlashEvent> flashEventObserver = event -> {
        if (event instanceof FlashEvent.Progress progress) {
            int percent = progress.getPercent();
            status.setText(R.string.flashing_uploading);
            title.setText(String.format(getString(R.string.flashing_percent), percent));
            progressBar.setProgress(percent);
        } else if (event instanceof FlashEvent.Done done
                && done.getResult() instanceof FlashResult.Success) {
            Log.d(TAG, "flash completed, calling finishActivity()");
            status.setText(R.string.flashing_completed);
            progressBar.showCompleted();
            finishActivity();
        }
        // Done(Failure) is rendered from the sticky mode (AppMode.Error) so
        // that a rotation after the failure restores the same screen.
    };

    private final Consumer<AppMode> modeObserver = mode -> {
        if (mode instanceof AppMode.Flashing flashing) {
            binding.retryButton.setVisibility(View.INVISIBLE);
            switch (flashing.getPhase()) {
                case PREPARING -> status.setText(R.string.flashing_process_starting);
                case CONNECTING -> {
                    status.setText(R.string.flashing_device_connecting);
                    progressBar.showConnecting();
                }
                // Same phase, different mechanism: partial flashing resets the
                // board into its pairing mode, Nordic DFU into the bootloader.
                case REBOOTING -> status.setText(flashing.getMode() == FlashMode.PARTIAL
                        ? R.string.flashing_rebooting_partial
                        : R.string.flashing_enabling_dfu_mode);
                case UPLOADING -> status.setText(R.string.flashing_uploading);
                case FINALIZING -> status.setText(R.string.flashing_firmware_validating);
                case DISCONNECTING -> status.setText(R.string.flashing_device_disconnecting);
            }
        } else if (mode instanceof AppMode.Error error) {
            Log.d(TAG, "modeObserver: error=" + error);
            if (error.getCode() != AppMode.Error.NO_CODE) {
                status.setText(String.format(getString(R.string.flashing_error), error.getCode(), error.getMessage()));
            } else if (error.getMessage() != null) {
                status.setText(error.getMessage());
            }
            progressBar.showFailed();
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
        RepoObserve.flashEvents(this, flashEventObserver);
        RepoObserve.mode(this, modeObserver);

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
