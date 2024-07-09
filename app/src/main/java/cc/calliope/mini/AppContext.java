package cc.calliope.mini;

import android.content.Context;

public class AppContext {
    private static AppContext instance;
    private final Context context;

    private AppContext(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void initialize(Context context) {
        if (instance == null) {
            instance = new AppContext(context);
        }
    }

    public static AppContext getInstance() {
        if (instance == null) {
            throw new IllegalStateException("AppContext is not initialized, call initialize(context) method first.");
        }
        return instance;
    }

    public Context getContext() {
        return context;
    }
}