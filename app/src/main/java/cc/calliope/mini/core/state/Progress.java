package cc.calliope.mini.core.state;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class Progress {
    private int percent;

    public Progress(int percent) {
        this.percent = percent;
    }

    public int getPercent() {
        return percent;
    }

    public void setPercent(int percent) {
        this.percent = percent;
    }

}
