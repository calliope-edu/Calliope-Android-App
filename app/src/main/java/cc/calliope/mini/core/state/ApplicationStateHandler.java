package cc.calliope.mini.core.state;

import android.content.Context;

import cc.calliope.mini.AppContext;

public class ApplicationStateHandler {

    private static final StateLiveData stateLiveData = new StateLiveData();
    private static final NotificationLiveData notificationLiveData = new NotificationLiveData();
    private static final ProgressLiveData progressLiveData = new ProgressLiveData();
    private static final ErrorLiveData errorLiveData = new ErrorLiveData();

    public static void updateState(@State.StateType int type){
        stateLiveData.setState(type);
    }

    public static void updateNotification(@Notification.NotificationType int type, String message){
        notificationLiveData.setNotification(type, message);
    }

    public static void updateNotification(@Notification.NotificationType int type, int stringId){
        notificationLiveData.setNotification(type, getString(stringId));
    }

    public static void updateProgress(int percent){
        progressLiveData.setProgress(percent);
    }

    public static void updateError(int code, String message){
        errorLiveData.setError(code, message);
    }

    public static StateLiveData getStateLiveData() {
        return stateLiveData;
    }

    public static NotificationLiveData getNotificationLiveData() {
        return notificationLiveData;
    }

    public static ProgressLiveData getProgressLiveData() {
        return progressLiveData;
    }

    public static ErrorLiveData getErrorLiveData() {
        return errorLiveData;
    }

    private static String getString(int stringId){
        Context context = AppContext.getInstance().getContext();
        return context.getString(stringId);
    }
}