package cc.calliope.mini.service;

import static cc.calliope.mini.state.State.STATE_BUSY;
import static cc.calliope.mini.state.State.STATE_FLASHING;
import static cc.calliope.mini.state.State.STATE_READY;

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
import cc.calliope.mini.activity.NotificationActivity;
import cc.calliope.mini.state.ApplicationStateHandler;
import cc.calliope.mini.state.Notification;
import cc.calliope.mini.state.State;
import cc.calliope.mini.utils.Utils;
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
        ApplicationStateHandler.updateState(STATE_BUSY);
        ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.flashing_device_connecting));
        // Enable Notification Channel for Android OREO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(getApplicationContext());
        }

        IntentFilter dfuServiceFilter = new IntentFilter();
        dfuServiceFilter.addAction(BROADCAST_PROGRESS);
        dfuServiceFilter.addAction(BROADCAST_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, dfuServiceFilter);

        super.onCreate();
    }

    @Override
    public void onDestroy() {
        //ApplicationStateHandler.updateState(STATE_READY);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(@Nullable final Intent intent) {
//        assert intent != null;
//        final long delay = intent.getLongExtra(DfuBaseService.EXTRA_SCAN_DELAY, 200);
//        waitFor(delay);
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

            switch (action) {
                case BROADCAST_PROGRESS -> {
                    int extra = intent.getIntExtra(EXTRA_DATA, 0);
                    ApplicationStateHandler.updateProgress(extra);
                    switch (extra) {
                        case PROGRESS_UPLOADING -> {
                            String message = getString(R.string.flashing_uploading);
                            ApplicationStateHandler.updateNotification(Notification.INFO, message);
                            ApplicationStateHandler.updateState(STATE_FLASHING);
                        }
                        case PROGRESS_COMPLETED -> {
                            String message = getString(R.string.flashing_completed);
                            ApplicationStateHandler.updateNotification(Notification.INFO, message);
                            ApplicationStateHandler.updateState(STATE_READY);
                        }
                        case PROGRESS_ABORTED -> {
                            String message = getString(R.string.flashing_aborted);
                            ApplicationStateHandler.updateNotification(Notification.INFO, message);
                            ApplicationStateHandler.updateState(STATE_READY);
                        }
                        default -> {
                            ApplicationStateHandler.updateState(STATE_FLASHING);
                        }
                    }
                }
                case BROADCAST_ERROR -> {
                    int code = intent.getIntExtra(EXTRA_DATA, 0);
                    int type = intent.getIntExtra(EXTRA_ERROR_TYPE, 0);
                    String message = switch (type) {
                        case ERROR_TYPE_COMMUNICATION_STATE ->
                                GattError.parseConnectionError(code);
                        case ERROR_TYPE_DFU_REMOTE ->
                                GattError.parseDfuRemoteError(code);
                        default -> GattError.parse(code);
                    };

                    Utils.log(Log.ERROR, TAG, "Error (" + code + "): " + message);
                    ApplicationStateHandler.updateError(code, message);
                }
            }
        }
    };
}
