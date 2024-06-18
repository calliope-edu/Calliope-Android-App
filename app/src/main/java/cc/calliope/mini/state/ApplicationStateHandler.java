package cc.calliope.mini.state;

import cc.calliope.mini.R;

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

    public static void updateProgress(int percent){
        progressLiveData.setProgress(percent);
    }

    public static void updateError(int code, String message){
        errorLiveData.setError(code, message);
        stateLiveData.setState(State.STATE_ERROR);
        progressLiveData.setProgress(0);
        notificationLiveData.setNotification(Notification.ERROR, message);
    }

    public static void updateError(String message){
        updateError(133, message);
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
}