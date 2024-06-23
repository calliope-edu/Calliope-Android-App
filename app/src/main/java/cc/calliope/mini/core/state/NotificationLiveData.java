package cc.calliope.mini.core.state;

import androidx.lifecycle.LiveData;

public class NotificationLiveData extends LiveData<Notification> {

    public void setNotification(int type, String message){
//        Notification notification = getValue();
//        if(notification != null && notification.getType() == type && notification.getMessage().equals(message)){
//            return;
//        }
        postValue(new Notification(type, message));
    }
}