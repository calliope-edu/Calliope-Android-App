package cc.calliope.mini.notification;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class Notification {

    public static final int TYPE_INFO = 0;
    public static final int TYPE_WARNING = 1;
    public static final int TYPE_ERROR = 2;

    @IntDef({TYPE_INFO, TYPE_WARNING, TYPE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface NotificationType {
    }

    private int type;
    private String message;

    public Notification(int type, String message) {
        this.type = type;
        this.message = message;
    }

    @NotificationType
    public int getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public void setType(@NotificationType int type) {
        this.type = type;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
