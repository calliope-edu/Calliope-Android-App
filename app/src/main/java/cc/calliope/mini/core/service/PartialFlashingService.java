package cc.calliope.mini.core.service;

import static cc.calliope.mini.core.state.Notification.INFO;

import android.app.Activity;


import org.microbit.android.partialflashing.PartialFlashingBaseService;

import cc.calliope.mini.activity.NotificationActivity;
import cc.calliope.mini.core.state.ApplicationStateHandler;

public class PartialFlashingService extends PartialFlashingBaseService {
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @Override
    public void onCreate() {
        ApplicationStateHandler.updateNotification(INFO, "Partial Flashing in progress...");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}