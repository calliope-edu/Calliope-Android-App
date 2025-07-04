package cc.calliope.mini.ui.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.shape.ShapeAppearanceModel;

import cc.calliope.mini.utils.Utils;

public class MovableFloatingActionButton extends FloatingActionButton implements View.OnTouchListener{
    private final static float CLICK_DRAG_TOLERANCE = 10; // Often, there will be a slight, unintentional, drag when the user taps the FAB, so we need to account for this.
    private float downRawX, downRawY;
    private float dX, dY;
    private Paint paint;
    private Context context;
    private int actionBarSize;
    private int progress = 0;

    private final RectF bounds = new RectF();
    private final Path fullPath = new Path();
    private final Path progressPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();

    private static final int RADIUS_DP = 20;

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

        setShapeAppearanceModel(ShapeAppearanceModel.builder()
            .setAllCornerSizes(Utils.convertDpToPixel(context, RADIUS_DP)).build()
        );

        setOnTouchListener(this);
        paint = new Paint();

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
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
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

    public void setProgress(int percent) {
        this.progress = Math.max(percent, 0);
        invalidate();
    }

    public void setColor(int resId) {
        int color = context.getColor(resId);
        setBackgroundTintList(ColorStateList.valueOf(color));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int STROKE_DP = 4;

        int strokeWidth = Utils.convertDpToPixel(getContext(), STROKE_DP);
        float radius = Utils.convertDpToPixel(getContext(), RADIUS_DP-(STROKE_DP/2));
        int width = getWidth();
        int height = getHeight();

        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAntiAlias(true);

        bounds.set(
                strokeWidth / 2f,
                strokeWidth / 2f,
                width - strokeWidth / 2f,
                height - strokeWidth / 2f
        );

        fullPath.reset();
        fullPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);

        pathMeasure.setPath(fullPath, true);
        float pathLength = pathMeasure.getLength();
        float progressLength = pathLength * (progress / 100f);

        progressPath.reset();
        pathMeasure.getSegment(0, progressLength, progressPath, true);

        canvas.drawPath(progressPath, paint);
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