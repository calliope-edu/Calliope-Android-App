package cc.calliope.mini.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import cc.calliope.mini.service.DfuService;

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
            turnedOnAllLed(true);
//            setLed(true, 2, 3, 4, 6, 10, 17, 19);
        } else {
            int p = Math.max(progress, 0);
            int max = p / 4;

            for (int i = 0; i <= max; i++) {
                turnedOnLed(true, i);
            }
        }
        invalidate();
    }
}