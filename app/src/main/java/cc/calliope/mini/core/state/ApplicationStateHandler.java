package cc.calliope.mini.core.state;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import cc.calliope.mini.AppContext;

public class ApplicationStateHandler {

    private static final MutableLiveData<State> stateLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Event<Notification>> notificationLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Progress> progressLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Error> errorLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Boolean> isDeviceAvailable = new MutableLiveData<>();

    public static void updateState(@State.StateType int type){
        stateLiveData.setValue(new State(type));
    }

    public static void updateNotification(@Notification.NotificationType int type, String message){
        notificationLiveData.postValue(new Event<>(new Notification(type, message)));
    }

    public static void updateNotification(@Notification.NotificationType int type, int stringId){
        String message = getString(stringId);
        notificationLiveData.postValue(new Event<>(new Notification(type, message)));
    }

    public static void updateProgress(int percent){
        progressLiveData.postValue(new Progress(percent));
    }

    public static void updateError(int code, String message){
        errorLiveData.postValue(new Error(code, message));
    }

    public static void updateDeviceAvailability(boolean isAvailable){
        isDeviceAvailable.postValue(isAvailable);
    }

    public static LiveData<State> getStateLiveData() {
        return stateLiveData;
    }

    public static LiveData<Event<Notification>> getNotificationLiveData() {
        return notificationLiveData;
    }

    public static LiveData<Progress> getProgressLiveData() {
        return progressLiveData;
    }

    public static LiveData<Error> getErrorLiveData() {
        return errorLiveData;
    }

    private static String getString(int stringId){
        Context context = AppContext.getInstance().getContext();
        return context.getString(stringId);
    }

    public static LiveData<Boolean> getDeviceAvailabilityLiveData() {
        return isDeviceAvailable;
    }
}