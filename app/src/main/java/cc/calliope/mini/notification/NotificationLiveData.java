package cc.calliope.mini.notification;

import androidx.lifecycle.LiveData;

public class NotificationLiveData extends LiveData<Notification> {

    public void setNotification(int type, String message){
        postValue(new Notification(type, message));
    }
}