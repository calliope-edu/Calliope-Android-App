package cc.calliope.mini.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import cc.calliope.mini.core.service.DfuService;

public class BoardProgressBar extends BoardView {

    public BoardProgressBar(Context context) {
        super(context);
    }

    public BoardProgressBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BoardProgressBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setProgress(int progress) {
        if (progress == DfuService.PROGRESS_COMPLETED) {
            turnedOnAllLed(false);
            turnedOnLed(true, 2, 6, 12, 18, 24);
        } else if (progress == DfuService.PROGRESS_CONNECTING) {
            turnedOnAllLed(false);
            turnedOnLed(true, 7, 8, 9, 12, 14, 17, 18, 19);
        } else if (progress == DfuService.PROGRESS_ABORTED) {
            turnedOnAllLed(false);
            turnedOnLed(true, 1, 5, 7, 9, 13, 17, 19, 21, 25);
        } else if (progress >= 0 || progress <= 100) {
            if (progress == 0) {
                turnedOnAllLed(false);
            }
            int p = Math.max(progress, 0);
            int max = p / 4;

            for (int i = 0; i <= max; i++) {
                turnedOnLed(true, i);
            }
        } else {
            turnedOnAllLed(false);
        }
        invalidate();
    }
}