package cc.calliope.mini.state;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class Notification {

    public static final int INFO = 0;
    public static final int WARNING = 1;
    public static final int ERROR = 2;

    @IntDef({INFO, WARNING, ERROR})
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
