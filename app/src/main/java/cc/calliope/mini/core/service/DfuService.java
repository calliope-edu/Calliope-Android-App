package cc.calliope.mini.core.service;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import cc.calliope.mini.BuildConfig;
import cc.calliope.mini.R;
import cc.calliope.mini.core.state.AppMode;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.FlashPhase;
import cc.calliope.mini.core.state.FlashResult;
import cc.calliope.mini.core.state.Notification;
import cc.calliope.mini.ui.activity.NotificationActivity;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.error.GattError;

/**
 * Nordic DFU worker. Translates the library's local broadcasts (its only
 * complete feed — see {@link #onCreate()}) into the app's flash model:
 * {@link FlashPhase} for the negative {@code PROGRESS_*} sentinels,
 * {@link AppStateRepository#flashProgress(int)} for 0..100, and a single
 * {@link AppStateRepository#finishFlash(FlashResult)} at the end. The
 * Nordic constants stop here; nothing outside this class sees them.
 */
public class DfuService extends DfuBaseService {
    static final String TAG = "DfuService";
    private static final int PROGRESS_UPLOADING = 0;

    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // FlashingService owns the session and has already switched it to
        // FULL_DFU; the library broadcasts PROGRESS_CONNECTING itself.
        AppStateRepository.updateNotification(Notification.WARNING, getString(R.string.flashing_device_connecting));
        // Enable Notification Channel for Android OREO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(getApplicationContext());
        }

        // The local broadcasts are DfuBaseService's only complete feed:
        // the updateProgressNotification hook is throttled to one call per
        // 250 ms (sentinel transitions like PROGRESS_DISCONNECTING can be
        // swallowed), and errors expose their code/type via BROADCAST_ERROR
        // only. So the receiver stays and republishes into the repository.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_PROGRESS);
        filter.addAction(BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
    }

    @Override
    protected void onHandleIntent(@Nullable final Intent intent) {
        super.onHandleIntent(intent);
    }

    @Override
    protected boolean isDebug() {
        // Here return true if you want the service to print more logs in LogCat.
        // Library's BuildConfig in current version of Android Studio is always set to DEBUG=false, so
        // make sure you return true or your.app.BuildConfig.DEBUG here.
        return BuildConfig.DEBUG;
    }

    @Override
    protected void updateProgressNotification(@NonNull final NotificationCompat.Builder builder, final int progress) {
        // Remove Abort action from the notification
    }

    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                return;
            }

            if (BROADCAST_PROGRESS.equals(action)) {
                onProgress(intent.getIntExtra(EXTRA_DATA, 0));
                return;
            }

            if (BROADCAST_ERROR.equals(action)) {
                onError(intent.getIntExtra(EXTRA_DATA, 0), intent.getIntExtra(EXTRA_ERROR_TYPE, 0));
            }
        }
    };

    private void onProgress(int value) {
        switch (value) {
            case PROGRESS_CONNECTING -> AppStateRepository.flashPhase(FlashPhase.CONNECTING);
            case PROGRESS_STARTING -> AppStateRepository.flashPhase(FlashPhase.PREPARING);
            case PROGRESS_ENABLING_DFU_MODE -> AppStateRepository.flashPhase(FlashPhase.REBOOTING);
            case PROGRESS_VALIDATING -> AppStateRepository.flashPhase(FlashPhase.FINALIZING);
            case PROGRESS_DISCONNECTING -> AppStateRepository.flashPhase(FlashPhase.DISCONNECTING);
            case PROGRESS_COMPLETED -> {
                AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_completed));
                AppStateRepository.finishFlash(FlashResult.Success.INSTANCE);
            }
            case PROGRESS_ABORTED -> {
                String message = getString(R.string.flashing_aborted);
                AppStateRepository.updateNotification(Notification.INFO, message);
                AppStateRepository.finishFlash(new FlashResult.Failure(AppMode.Error.NO_CODE, message));
            }
            default -> {
                if (value == PROGRESS_UPLOADING) {
                    AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_uploading));
                }
                AppStateRepository.flashPhase(FlashPhase.UPLOADING);
                AppStateRepository.flashProgress(value);
            }
        }
    }

    private void onError(int code, int type) {
        String message = switch (type) {
            case ERROR_TYPE_COMMUNICATION_STATE -> GattError.parseConnectionError(code);
            case ERROR_TYPE_DFU_REMOTE -> GattError.parseDfuRemoteError(code);
            default -> GattError.parse(code);
        };

        Log.e(TAG, "Error (" + code + "): " + message);
        AppStateRepository.updateNotification(Notification.ERROR, R.string.error_connection_failed);
        AppStateRepository.finishFlash(new FlashResult.Failure(code, message));
    }
}
