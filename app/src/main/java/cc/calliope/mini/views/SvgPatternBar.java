package cc.calliope.mini.views;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.VectorDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.drawable.shapes.Shape;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.RatingBar;

import androidx.appcompat.graphics.drawable.DrawableWrapper;
import androidx.appcompat.widget.AppCompatRatingBar;
import androidx.preference.PreferenceManager;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import java.lang.reflect.Method;

import cc.calliope.mini.R;
import cc.calliope.mini.utils.Utils;

public class SvgPatternBar extends AppCompatRatingBar {
    private Bitmap sampleTile;
    private int column;

    public SvgPatternBar(Context context) {
        this(context, null);
    }

    public SvgPatternBar(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.appcompat.R.attr.ratingBarStyle);
    }

    public SvgPatternBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SvgPatternBar);
        String methodName;
        try {
            column = a.getInteger(R.styleable.SvgPatternBar_column, 0); // Default value is 0
            methodName = a.getString(R.styleable.SvgPatternBar_onChange);
        } finally {
            a.recycle();
        }

        if (methodName != null) {
            setOnChangeListener(new DeclaredOnChangeListener(methodName, column));
        }

        init();
    }

    private void init() {
        LayerDrawable drawable = (LayerDrawable) createTile(getProgressDrawable(), false);
        setProgressDrawable(drawable);
        setValue(loadValue());
    }

    private class DeclaredOnChangeListener implements OnRatingBarChangeListener {
        private final String methodName;
        private final int column;

        public DeclaredOnChangeListener(String methodName, int column) {
            this.methodName = methodName;
            this.column = column;
        }

        @Override
        public void onRatingChanged(RatingBar ratingBar, float value, boolean fromUser) {
            Activity activity = getActivity();
            if (value < 1.0f) {
                value = 1.0f;
                ratingBar.setRating(value);
            }

            if (activity == null) {
                return;
            }

            try {
                Utils.log(Log.ASSERT, "TEST", "Context: " + getContext());
                Method method = activity.getClass().getMethod(methodName, int.class, float.class);
                method.invoke(activity, column, value);
                saveValue(value);
            } catch (Exception e) {
                throw new IllegalStateException("Could not execute non-public method " + methodName + " for onChange", e);
            }
        }
    }

    private Activity getActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public void setOnChangeListener(OnRatingBarChangeListener listener) {
        super.setOnRatingBarChangeListener(listener);
    }

    /**
     * Converts a drawable to a tiled version of itself. It will recursively
     * traverse layer and state list drawables.
     */
    @SuppressLint("RestrictedApi")
    private Drawable createTile(Drawable drawable, boolean clip) {
        if (drawable instanceof DrawableWrapper) {
            Drawable inner = ((DrawableWrapper) drawable).getWrappedDrawable();
            if (inner != null) {
                inner = createTile(inner, clip);
                ((DrawableWrapper) drawable).setWrappedDrawable(inner);
            }
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable background = (LayerDrawable) drawable;
            final int n = background.getNumberOfLayers();
            Drawable[] outDrawables = new Drawable[n];

            for (int i = 0; i < n; i++) {
                int id = background.getId(i);
                outDrawables[i] = createTile(background.getDrawable(i),
                        (id == android.R.id.progress || id == android.R.id.secondaryProgress));
            }
            LayerDrawable newBg = new LayerDrawable(outDrawables);

            for (int i = 0; i < n; i++) {
                newBg.setId(i, background.getId(i));
            }

            return newBg;

        } else if (drawable instanceof BitmapDrawable) {
            final BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            final Bitmap tileBitmap = bitmapDrawable.getBitmap();
            if (sampleTile == null) {
                sampleTile = tileBitmap;
            }

            final ShapeDrawable shapeDrawable = new ShapeDrawable(getDrawableShape());
            final BitmapShader bitmapShader = new BitmapShader(tileBitmap,
                    Shader.TileMode.REPEAT, Shader.TileMode.CLAMP);
            shapeDrawable.getPaint().setShader(bitmapShader);
            shapeDrawable.getPaint().setColorFilter(bitmapDrawable.getPaint().getColorFilter());
            return (clip) ? new ClipDrawable(shapeDrawable, Gravity.START,
                    ClipDrawable.HORIZONTAL) : shapeDrawable;
        } else if (drawable instanceof VectorDrawable) {
            return createTile(getBitmapDrawableFromVectorDrawable(drawable), clip);
        } else if (drawable instanceof VectorDrawableCompat) {
            // API 19 support.
            return createTile(getBitmapDrawableFromVectorDrawable(drawable), clip);
        }
        return drawable;
    }

    private BitmapDrawable getBitmapDrawableFromVectorDrawable(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return new BitmapDrawable(getResources(), bitmap);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (sampleTile != null) {
            final int width = sampleTile.getWidth() * getNumStars();
            setMeasuredDimension(resolveSizeAndState(width, widthMeasureSpec, 0),
                    getMeasuredHeight());
        }
    }

    private Shape getDrawableShape() {
        final float[] roundedCorners = new float[]{5, 5, 5, 5, 5, 5, 5, 5};
        return new RoundRectShape(roundedCorners, null, null);
    }

    public void setValue(float value) {
        setRating(value);
    }

    public float getValue() {
        return getRating();
    }

    public int getColumn() {
        return column;
    }

    public void setColumn(int column) {
        this.column = column;
    }

    //TODO Load current device and decode column = num of letter
    public float loadValue() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        float value = sharedPreferences.getFloat("pattern_column_" + column, 1f);
        Utils.log(Log.ASSERT, "BAR", "loadValue column: " + column + " value: " + value);
        return value;
    }

    public void saveValue(float value) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        Utils.log(Log.ASSERT, "BAR", "saveValue column: " + column + " value: " + value);
        sharedPreferences.edit().putFloat("pattern_column_" + column, value).apply();
    }
}