package cc.calliope.mini.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import cc.calliope.mini.R;

public class BoardView extends View {

    @IntDef({
            1,  2,  3,  4,  5,
            6,  7,  8,  9,  10,
            11, 12, 13, 14, 15,
            16, 17, 18, 19, 20,
            21, 22, 23, 24, 25
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LedRange {}
    private int aspectRatioWidth = 1;
    private int aspectRatioHeight = 1;
    private Drawable drawable;
    private final boolean[] ledArray = new boolean[26];

    public BoardView(Context context) {
        super(context);
        init();
    }

    public BoardView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BoardView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setImageResource(R.drawable.layers_board);
        turnedOnAllLed(false);
    }

    public void setImageResource(int resId) {
        drawable = ResourcesCompat.getDrawable(getResources(), resId, null);
    }

    public void turnedOnLed(boolean turnedOn, @LedRange int led) {
        if (led > 0 && led < ledArray.length) {
            ledArray[led] = turnedOn;
        }
    }

    public void turnedOnLed(boolean turnedOn, @LedRange int... led) {
        for (int j : led) {
            if (j > 0 && j < ledArray.length) {
                ledArray[j] = turnedOn;
            }
        }
    }

    public void turnedOnAllLed(boolean turnedOn) {
        Arrays.fill(ledArray, turnedOn);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int originalWidth = MeasureSpec.getSize(widthMeasureSpec);
        int originalHeight = MeasureSpec.getSize(heightMeasureSpec);
        int calculatedHeight = originalWidth * aspectRatioHeight / aspectRatioWidth;

        int finalWidth, finalHeight;
        finalWidth = originalWidth;
        finalHeight = calculatedHeight;
        if (calculatedHeight > originalHeight) {
            finalHeight = originalHeight;
            finalWidth = originalHeight * aspectRatioWidth / aspectRatioHeight;
        }

        super.onMeasure(
                MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (drawable instanceof LayerDrawable) {
            int width = getWidth();
            int height = getHeight();
            LayerDrawable layerDrawable = (LayerDrawable) drawable;

            Drawable background = layerDrawable.findDrawableByLayerId(R.id.background);
            background.setBounds(0, 0, width, height);
            background.draw(canvas);

            int max = Math.min(ledArray.length, layerDrawable.getNumberOfLayers());

            for (int i = 1; i < max; i++) {
                if (ledArray[i]) {
                    Drawable layer = layerDrawable.getDrawable(i);
                    layer.setBounds(0, 0, width, height);
                    layer.draw(canvas);
                }
            }
        }
        super.onDraw(canvas);
    }

    public void setAspectRatio(int aspectRatioWidth, int aspectRatioHeight) {
        this.aspectRatioWidth = aspectRatioWidth;
        this.aspectRatioHeight = aspectRatioHeight;
        requestLayout();
    }

    public int getAspectRatioWidth() {
        return aspectRatioWidth;
    }

    public int getAspectRatioHeight() {
        return aspectRatioHeight;
    }
}