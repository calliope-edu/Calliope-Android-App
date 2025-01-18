package cc.calliope.mini.core.service;

import static android.app.Activity.RESULT_OK;
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
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.microbit.android.partialflashing.PartialFlashingBaseService;

import cc.calliope.mini.R;
import cc.calliope.mini.activity.NotificationActivity;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Notification;
import no.nordicsemi.android.dfu.DfuServiceInitiator;


public class PartialFlashingService extends PartialFlashingBaseService {
    static final String TAG = "PartialFlashingService";

    private ResultReceiver resultReceiver = null;

    @Override
    public void logi(String message) {
        Log.i(TAG, "### " + Thread.currentThread().getId() + " # " + message);

    }

    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @SuppressWarnings("deprecation")
    private ResultReceiver getParcelableExtra(Intent intent, String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent != null ? intent.getParcelableExtra(name, ResultReceiver.class) : null;
        } else {
            return intent != null ? intent.getParcelableExtra(name) : null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Log.d(TAG, "Service started");

        // Get the pending intent from the intent
        resultReceiver = getParcelableExtra(intent, "resultReceiver");
        Log.i(TAG, "ResultReceiver: " + resultReceiver);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        ApplicationStateHandler.updateState(STATE_BUSY);
        ApplicationStateHandler.updateNotification(Notification.WARNING, getString(R.string.flashing_device_connecting));
        // Enable Notification Channel for Android OREO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(getApplicationContext());
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_PROGRESS);
        filter.addAction(BROADCAST_START);
        filter.addAction(BROADCAST_COMPLETE);
        filter.addAction(EXTRA_PROGRESS);
        filter.addAction(BROADCAST_PF_FAILED);
        filter.addAction(BROADCAST_PF_ATTEMPT_DFU);
        filter.addAction(BROADCAST_PF_ABORTED);
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        //ApplicationStateHandler.updateState(STATE_READY);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
    }

    private void sendResult(boolean result) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("result", result);
        if (resultReceiver != null) {
            resultReceiver.send(RESULT_OK, bundle);
        }
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
                    final int progress = intent.getIntExtra(EXTRA_PROGRESS, 0);
                    ApplicationStateHandler.updateState(STATE_FLASHING);
                    ApplicationStateHandler.updateProgress(progress);
                }
                case BROADCAST_START -> {
                    String message = getString(R.string.flashing_uploading);
                    ApplicationStateHandler.updateNotification(Notification.INFO, message);
                    ApplicationStateHandler.updateState(STATE_BUSY);
                }
                case BROADCAST_COMPLETE -> {
                    String message = getString(R.string.flashing_completed);
                    ApplicationStateHandler.updateNotification(Notification.INFO, message);
                    ApplicationStateHandler.updateState(STATE_IDLE);
                    ApplicationStateHandler.updateProgress(0);
                    sendResult(true);
                }
                case BROADCAST_PF_FAILED, BROADCAST_PF_ATTEMPT_DFU, BROADCAST_PF_ABORTED -> {
                    final int data = intent.getIntExtra(EXTRA_DATA, 0);
                    Log.e(TAG, "Partial flashing failed");
                    ApplicationStateHandler.updateState(STATE_BUSY);
                    ApplicationStateHandler.updateNotification(Notification.INFO, "Partial flashing failed, attempting full flashing");
                    //ApplicationStateHandler.updateState(STATE_ERROR);
                    //ApplicationStateHandler.updateNotification(Notification.ERROR, R.string.error_connection_failed);
                    //ApplicationStateHandler.updateError(data, "Partial flashing failed");
                    sendResult(false);
                }
            }
        }
    };
}