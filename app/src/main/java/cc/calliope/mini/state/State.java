package cc.calliope.mini.state;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class State {

    public static final int STATE_INITIALIZATION = -1;
    public static final int STATE_READY = 0;
    public static final int STATE_FLASHING = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_ERROR = 3;

    @IntDef({STATE_INITIALIZATION, STATE_READY, STATE_FLASHING, STATE_COMPLETED, STATE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface StateType {
    }

    private int type;
    private String message;

    public State(int type, String message) {
        this.type = type;
        this.message = message;
    }

    @StateType
    public int getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public void setType(@StateType int type) {
        this.type = type;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
