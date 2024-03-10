package cc.calliope.mini.notification;

public class NotificationManager {

    private static final NotificationLiveData notificationLiveData = new NotificationLiveData();

    public static void updateNotificationMessage(int type, String message){
        notificationLiveData.setNotification(type, message);
    }

    public static NotificationLiveData getNotificationLiveData() {
        return notificationLiveData;
    }
}