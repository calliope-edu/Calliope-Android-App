package cc.calliope.mini.core.service;

import static cc.calliope.mini.core.state.Notification.INFO;
import static cc.calliope.mini.core.state.State.STATE_FLASHING;
import static cc.calliope.mini.core.state.State.STATE_IDLE;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.microbit.android.partialflashing.PartialFlashingBaseService;

import cc.calliope.mini.activity.NotificationActivity;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.core.state.Notification;

public class PartialFlashingService extends PartialFlashingBaseService {
    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
    public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";
    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";

    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationStateHandler.updateNotification(INFO, "Partial Flashing in progress...");

        IntentFilter filter = new IntentFilter();
        filter.addAction(BROADCAST_PROGRESS);
        filter.addAction(BROADCAST_START);
        filter.addAction(BROADCAST_COMPLETE);
        filter.addAction(BROADCAST_PF_FAILED);
        filter.addAction(BROADCAST_PF_ATTEMPT_DFU);
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(progressReceiver);
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
                    int progress = intent.getIntExtra(EXTRA_PROGRESS, 0);
                    ApplicationStateHandler.updateState(STATE_FLASHING);
                    ApplicationStateHandler.updateProgress(progress);
                }
                case BROADCAST_START -> {
                    ApplicationStateHandler.updateNotification(Notification.INFO, "BROADCAST_START");
                    ApplicationStateHandler.updateState(STATE_FLASHING);
                }
                case BROADCAST_COMPLETE -> {
                    ApplicationStateHandler.updateNotification(Notification.INFO, "BROADCAST_COMPLETE");
                    ApplicationStateHandler.updateProgress(0);
                    ApplicationStateHandler.updateState(STATE_IDLE);
                }
                case BROADCAST_PF_FAILED -> {
                    ApplicationStateHandler.updateNotification(Notification.ERROR, "BROADCAST_PF_FAILED");
                    ApplicationStateHandler.updateProgress(0);
                    ApplicationStateHandler.updateState(STATE_IDLE);
                }
                case BROADCAST_PF_ATTEMPT_DFU -> {
                    ApplicationStateHandler.updateNotification(Notification.ERROR, "BROADCAST_PF_ATTEMPT_DFU");
                    ApplicationStateHandler.updateProgress(0);
                    ApplicationStateHandler.updateState(STATE_IDLE);
                }
            }
        }
    };
}


