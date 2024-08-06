package cc.calliope.mini.core.state;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class State {

    public static final int STATE_ERROR = -2;
    public static final int STATE_IDLE = -1;
    public static final int STATE_READY = 0;
    public static final int STATE_BUSY = 1;
    public static final int STATE_FLASHING = 2;

    @IntDef({STATE_IDLE, STATE_BUSY, STATE_READY, STATE_FLASHING, STATE_ERROR})
    @Retention(RetentionPolicy.SOURCE)
    public @interface StateType {
    }

    private int type;

    public State(int type) {
        this.type = type;
    }

    @StateType
    public int getType() {
        return type;
    }

    public void setType(@StateType int type) {
        this.type = type;
    }

}
