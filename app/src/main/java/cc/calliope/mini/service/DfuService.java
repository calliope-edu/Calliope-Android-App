package cc.calliope.mini.service;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import cc.calliope.mini.App;
import cc.calliope.mini.BuildConfig;
import cc.calliope.mini.activity.NotificationActivity;
import cc.calliope.mini.utils.Version;
import no.nordicsemi.android.dfu.DfuBaseService;
import no.nordicsemi.android.dfu.DfuServiceInitiator;

public class DfuService extends DfuBaseService{
    private App app;

    @Override
    protected Class<? extends Activity> getNotificationTarget() {
        return NotificationActivity.class;
    }

    @Override
    public void onCreate() {
        // Enable Notification Channel for Android OREO
        if (Version.VERSION_O_AND_NEWER) {
            DfuServiceInitiator.createDfuNotificationChannel(getApplicationContext());
        }
        super.onCreate();
        app = (App) getApplication();
        app.setAppState(App.APP_STATE_FLASHING);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        app.setAppState(App.APP_STATE_STANDBY);
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
}
