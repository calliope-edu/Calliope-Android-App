package cc.calliope.mini.service;

import static cc.calliope.mini.notification.Notification.TYPE_INFO;

import android.app.Activity;


import org.microbit.android.partialflashing.PartialFlashingBaseService;

import cc.calliope.mini.activity.NotificationActivity;
import cc.calliope.mini.notification.NotificationManager;

public class PartialFlashingService extends PartialFlashingBaseService {
    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @Override
    public void onCreate() {
        NotificationManager.updateNotificationMessage(TYPE_INFO, "Partial Flashing in progress...");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}