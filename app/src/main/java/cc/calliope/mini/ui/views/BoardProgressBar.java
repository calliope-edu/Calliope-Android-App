package cc.calliope.mini.ui.views;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

/**
 * 5x5 LED matrix used as a flash progress indicator. Shows either a
 * percentage (LEDs lit row by row) or one of three symbolic patterns.
 */
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

    /** Lights LEDs proportionally to {@code percent} (0..100). */
    public void setProgress(int percent) {
        int p = Math.max(0, Math.min(percent, 100));
        if (p == 0) {
            turnedOnAllLed(false);
        }
        int max = p / 4;
        for (int i = 0; i <= max; i++) {
            turnedOnLed(true, i);
        }
        invalidate();
    }

    /** Bluetooth-ish glyph shown while connecting. */
    public void showConnecting() {
        turnedOnAllLed(false);
        turnedOnLed(true, 7, 8, 9, 12, 14, 17, 18, 19);
        invalidate();
    }

    /** Diagonal tick shown on success. */
    public void showCompleted() {
        turnedOnAllLed(false);
        turnedOnLed(true, 2, 6, 12, 18, 24);
        invalidate();
    }

    /** Cross shown on failure. */
    public void showFailed() {
        turnedOnAllLed(false);
        turnedOnLed(true, 1, 5, 7, 9, 13, 17, 19, 21, 25);
        invalidate();
    }
}
