package cc.calliope.mini.service;

import org.microbit.android.partialflashing.PartialFlashingBaseService;

import cc.calliope.mini.App;

public class PartialFlashingService extends PartialFlashingBaseService {
    private App app;

//    @Override
//    protected Class<? extends Activity> getNotificationTarget() {
//        return NotificationActivity.class;
//    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = (App) getApplication();
        app.setAppState(App.APP_STATE_FLASHING);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        app.setAppState(App.APP_STATE_STANDBY);
    }
}