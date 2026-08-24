package cc.calliope.mini.core.service;

import static cc.calliope.mini.core.state.State.STATE_BUSY;
import static cc.calliope.mini.core.state.State.STATE_ERROR;
import static cc.calliope.mini.core.state.State.STATE_FLASHING;
import static cc.calliope.mini.core.state.State.STATE_IDLE;


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
import cc.calliope.mini.ui.activity.NotificationActivity;
import cc.calliope.mini.core.state.AppStateRepository;
import cc.calliope.mini.core.state.Notification;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.error.GattError;

public class DfuService extends DfuBaseService{
    static final String TAG = "DfuService";
    static final int PROGRESS_UPLOADING = 0;
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        AppStateRepository.updateState(STATE_BUSY);
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
                int extra = intent.getIntExtra(EXTRA_DATA, 0);
                AppStateRepository.updateProgress(extra);
                switch (extra) {
                    case PROGRESS_UPLOADING -> {
                        AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_uploading));
                        AppStateRepository.updateState(STATE_FLASHING);
                    }
                    case PROGRESS_COMPLETED -> {
                        AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_completed));
                        AppStateRepository.updateState(STATE_IDLE);
                    }
                    case PROGRESS_ABORTED -> {
                        AppStateRepository.updateNotification(Notification.INFO, getString(R.string.flashing_aborted));
                        AppStateRepository.updateState(STATE_IDLE);
                    }
                    default -> AppStateRepository.updateState(STATE_FLASHING);
                }
                return;
            }

            if (!BROADCAST_ERROR.equals(action)) {
                return;
            }
            int code = intent.getIntExtra(EXTRA_DATA, 0);
            int type = intent.getIntExtra(EXTRA_ERROR_TYPE, 0);
            String message = switch (type) {
                case ERROR_TYPE_COMMUNICATION_STATE ->
                        GattError.parseConnectionError(code);
                case ERROR_TYPE_DFU_REMOTE ->
                        GattError.parseDfuRemoteError(code);
                default -> GattError.parse(code);
            };

            Log.e(TAG, "Error (" + code + "): " + message);
            // Publish the error before the state: state observers read the
            // error at the STATE_ERROR edge and would otherwise see the one
            // from a previous session.
            AppStateRepository.updateError(code, message);
            AppStateRepository.updateNotification(Notification.ERROR, R.string.error_connection_failed);
            AppStateRepository.updateState(STATE_ERROR);
        }
    };
}
