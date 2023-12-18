package cc.calliope.mini;

import android.app.Application;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;
import cc.calliope.mini.utils.Utils;

public class App extends Application {
    public static final int APP_STATE_STANDBY = 0;
    public static final int APP_STATE_CONNECTING = 1;
    public static final int APP_STATE_FLASHING = 2;
    public static final int APP_STATE_DISCONNECTING = 3;

    @IntDef({APP_STATE_STANDBY, APP_STATE_CONNECTING, APP_STATE_FLASHING, APP_STATE_DISCONNECTING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppState {
    }
    private int appState;

    public int getAppState() {
        return appState;
    }

    public void setAppState(@AppState int appState) {
        Utils.log(Log.ASSERT, "APP", "State: " + appState);
        this.appState = appState;
    }
}
