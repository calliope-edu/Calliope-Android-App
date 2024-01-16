package cc.calliope.mini.views;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.annotation.NonNull;

import cc.calliope.mini.ProgressCollector;
import cc.calliope.mini.ProgressListener;
import cc.calliope.mini.utils.Utils;

public class MovableFloatingActionButton extends FloatingActionButton implements View.OnTouchListener, ProgressListener {
    private final static float CLICK_DRAG_TOLERANCE = 10; // Often, there will be a slight, unintentional, drag when the user taps the FAB, so we need to account for this.
    private float downRawX, downRawY;
    private float dX, dY;
    private Paint paint;
    private RectF rectF;

    private int actionBarSize;
    private int progress = 0;
    private Context context;
    private ProgressCollector progressCollector;
//    private boolean flashing;

    public MovableFloatingActionButton(Context context) {
        super(context);
        init(context);
    }

    public MovableFloatingActionButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public MovableFloatingActionButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        this.context = context;
        progressCollector = new ProgressCollector(context);
        progressCollector.registerProgressListener(this);
        setOnTouchListener(this);
        paint = new Paint();
        rectF = new RectF();

        TypedValue typedValue = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
            actionBarSize = TypedValue.complexToDimensionPixelSize(typedValue.data, getResources().getDisplayMetrics());
        }

        setOnSystemUiVisibilityChangeListener(this::onFullscreenStateChanged);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
//        setProgress(0);
        progressCollector.registerReceivers();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        progressCollector.unregisterReceivers();
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) view.getLayoutParams();

        int action = motionEvent.getAction();
        if (action == MotionEvent.ACTION_DOWN) {

            downRawX = motionEvent.getRawX();
            downRawY = motionEvent.getRawY();
            dX = view.getX() - downRawX;
            dY = view.getY() - downRawY;

            return true; // Consumed

        } else if (action == MotionEvent.ACTION_MOVE) {

            int viewWidth = view.getWidth();
            int viewHeight = view.getHeight();

            View viewParent = (View) view.getParent();
            int parentWidth = viewParent.getWidth();
            int parentHeight = viewParent.getHeight();

            float newX = motionEvent.getRawX() + dX;
            newX = Math.max(layoutParams.leftMargin, newX); // Don't allow the FAB past the left hand side of the parent
            newX = Math.min(parentWidth - viewWidth - layoutParams.rightMargin, newX); // Don't allow the FAB past the right hand side of the parent

            float newY = motionEvent.getRawY() + dY;
            newY = Math.max(layoutParams.topMargin, newY); // Don't allow the FAB past the top of the parent
            newY = Math.min(parentHeight - viewHeight - layoutParams.bottomMargin, newY); // Don't allow the FAB past the bottom of the parent

            view.animate()
                    .x(newX)
                    .y(newY)
                    .setDuration(0)
                    .start();

            return true; // Consumed

        } else if (action == MotionEvent.ACTION_UP) {

            float upRawX = motionEvent.getRawX();
            float upRawY = motionEvent.getRawY();

            float upDX = upRawX - downRawX;
            float upDY = upRawY - downRawY;

            if (Math.abs(upDX) < CLICK_DRAG_TOLERANCE && Math.abs(upDY) < CLICK_DRAG_TOLERANCE) { // A click
                return performClick();
            } else { // A drag
                return true; // Consumed
            }

        } else {
            return super.onTouchEvent(motionEvent);
        }

    }

    @Override
    public void onDeviceConnecting() {
        setProgress(0);
    }

    @Override
    public void onProcessStarting() {
        setProgress(0);
    }

    @Override
    public void onEnablingDfuMode() {
        setProgress(0);
    }

    @Override
    public void onFirmwareValidating() {
        setProgress(0);
    }

    @Override
    public void onDeviceDisconnecting() {
        setProgress(0);
    }

    @Override
    public void onCompleted() {
        setProgress(0);
    }

    @Override
    public void onAborted() {
        setProgress(0);
    }

    @Override
    public void onProgressChanged(int percent) {
        setProgress(percent);
    }

    @Override
    public void onBonding(@NonNull BluetoothDevice device, int bondState, int previousBondState) {
    }

    @Override
    public void onAttemptDfuMode() {
    }

    @Override
    public void onStartDfuService(int hardwareVersion) {
    }

    @Override
    public void onError(int code, String message) {
        setProgress(0);
    }

    public void setProgress(int percent) {
        this.progress = Math.max(percent, 0);
        invalidate();
    }

//    public void setColor(int resId) {
//        int color;
//        if (Version.VERSION_M_AND_NEWER) {
//            color = context.getColor(resId);
//        } else {
//            color = getResources().getColor(resId);
//        }
//        setBackgroundTintList(ColorStateList.valueOf(color));
//    }

    @Override
    protected void onDraw(Canvas canvas) {
        int strokeWidth = Utils.convertDpToPixel(getContext(), 4);
        int width = getWidth();
        int height = getHeight();
        int sweepAngle = (int) (360 * (progress / 100.f));

        rectF.set(strokeWidth / 2.f, strokeWidth / 2.f, width - strokeWidth / 2.f, height - strokeWidth / 2.f);

        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(Paint.Style.STROKE);

        canvas.drawArc(rectF, 270, sweepAngle, false, paint);

        super.onDraw(canvas);
    }

    public void moveUp() {
        int x = Math.round(getX());
        int y = Math.round(getY());
        if (y > actionBarSize && y < actionBarSize * 2) {
            animate()
                    .x(x)
                    .y(y - actionBarSize)
                    .setDuration(0)
                    .start();
        }
    }

    public void moveDown() {
        int x = Math.round(getX());
        int y = Math.round(getY());
        if (y < actionBarSize) {
            animate()
                    .x(x)
                    .y(y + actionBarSize)
                    .setDuration(0)
                    .start();
        }
    }

    private void onFullscreenStateChanged(int visibility) {
        boolean fullScreen = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
        if (!fullScreen) {
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) getLayoutParams();
            View viewParent = (View) getParent();
            int parentWidth = viewParent.getWidth();
            int parentHeight = viewParent.getHeight();
            int x = Math.round(getX());
            int y = Math.round(getY());

            if (x + actionBarSize > parentWidth) {
                animate()
                        .x(parentWidth - actionBarSize - layoutParams.rightMargin)
                        .y(y)
                        .setDuration(0)
                        .start();
            }
            if (y + actionBarSize > parentHeight) {
                animate()
                        .x(x)
                        .y(parentHeight - actionBarSize - layoutParams.bottomMargin)
                        .setDuration(0)
                        .start();
            }
        }
    }
}