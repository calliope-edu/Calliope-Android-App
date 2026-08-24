package cc.calliope.mini.core.state;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import cc.calliope.mini.AppContext;

/**
 * Legacy static LiveData bus. Phase-2 migration: every mutator dual-writes
 * into {@link AppStateRepository}; readers are being moved to the repository
 * flows, after which this class is deleted.
 */
public class ApplicationStateHandler {

    private static final MutableLiveData<State> stateLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Event<Notification>> notificationLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Progress> progressLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Error> errorLiveData = new MutableLiveData<>();
    private static final MutableLiveData<Boolean> isDeviceAvailable = new MutableLiveData<>();

    public static void updateState(@State.StateType int type){
        stateLiveData.postValue(new State(type));
        AppStateRepository.updateState(type);
    }

    public static void updateNotification(@Notification.NotificationType int type, String message){
        notificationLiveData.postValue(new Event<>(new Notification(type, message)));
        AppStateRepository.updateNotification(type, message);
    }

    public static void updateNotification(@Notification.NotificationType int type, int stringId){
        String message = getString(stringId);
        notificationLiveData.postValue(new Event<>(new Notification(type, message)));
        AppStateRepository.updateNotification(type, message);
    }

    public static void updateProgress(int percent){
        progressLiveData.postValue(new Progress(percent));
        AppStateRepository.updateProgress(percent);
    }

    public static void updateError(int code, String message){
        errorLiveData.postValue(new Error(code, message));
        AppStateRepository.updateError(code, message);
    }

    public static void updateDeviceAvailability(boolean isAvailable){
        isDeviceAvailable.postValue(isAvailable);
        AppStateRepository.updateDeviceAvailability(isAvailable);
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