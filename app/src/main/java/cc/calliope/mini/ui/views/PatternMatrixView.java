package cc.calliope.mini.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import cc.calliope.mini.R;


public class PatternMatrixView extends View {

    private static final int SIZE = 5;
    private final boolean[][] cell = new boolean[SIZE][SIZE];
    private final String[][] letter = {
            {"Z", "V", "G", "P", "T"},
            {"U", "O", "I", "E", "A"}
    };
    private float cellSize;
    private Drawable cellOnDrawable;
    private Drawable cellOffDrawable;

    public interface OnPatternChangeListener {
        void onPatternChanged(String pattern);
    }

    private OnPatternChangeListener onPatternChangeListener;

    public PatternMatrixView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PatternMatrixView(Context context) {
        super(context);
        init();
    }

    private void init() {
        setCheckBoxDrawables(R.drawable.pattern_selected, R.drawable.pattern_unselected);
        for (int column = 0; column < SIZE; column++) {
            cell[column][SIZE - 1] = true;
        }
    }

    public void setOnPatternChangeListener(OnPatternChangeListener listener) {
        this.onPatternChangeListener = listener;
    }

    public void setCheckBoxDrawables(@DrawableRes int onDrawableRes, @DrawableRes int offDrawableRes) {
        cellOnDrawable = ContextCompat.getDrawable(getContext(), onDrawableRes);
        cellOffDrawable = ContextCompat.getDrawable(getContext(), offDrawableRes);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        cellSize = width / (float) SIZE;
        setMeasuredDimension(width, (int) (cellSize * SIZE));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < SIZE; i++) {
            for (int j = 0; j < SIZE; j++) {
                Drawable drawable = cell[i][j] ? cellOnDrawable : cellOffDrawable;
                int left = (int) (i * cellSize);
                int top = (int) (j * cellSize);
                int right = (int) ((i + 1) * cellSize);
                int bottom = (int) ((j + 1) * cellSize);

                drawable.setBounds(left, top, right, bottom);
                drawable.draw(canvas);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        int column = (int) (event.getX() / cellSize);
        int number = (int) (event.getY() / cellSize);

        if (action == MotionEvent.ACTION_DOWN) {
            if (column < SIZE && number < SIZE) {
                if (cell[column][number] && number < SIZE - 1 && (number == 0 || !cell[column][number - 1])) {
                    number++;
                }
                setColumn(column, number);

                if (onPatternChangeListener != null) {
                    onPatternChangeListener.onPatternChanged(getPattern());
                }
                return true;
            }
        }else if(action == MotionEvent.ACTION_UP){
            if (column < SIZE && number < SIZE) {
                return true;
            }else {
                return performClick();
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void setColumn(int column, int number) {
        if (column < SIZE && number < SIZE) {
            for (int i = 0; i < SIZE; i++) {
                cell[column][i] = false;
            }
            for (int i = number; i < SIZE; i++) {
                cell[column][i] = true;
            }
            invalidate();
        }
    }

    public String getPattern() {
        int[] counts = new int[SIZE];

        for (int column = 0; column < SIZE; column++) {
            for (int number = 0; number < SIZE; number++) {
                if (cell[column][number]) {
                    counts[column]++;
                }
            }
        }

        StringBuilder result = new StringBuilder();
        for (int column = 0; column < counts.length; column++) {
            result.append(letter[column % 2][counts[column] - 1]);
        }

        return result.toString();
    }

    public void setPattern(String pattern) {
        if (pattern == null || pattern.length() != SIZE) {
            throw new IllegalArgumentException("The string must contain " + SIZE + " letters.");
        }

        for (int i = 0; i < pattern.length(); i++) {
            String letterToFind = String.valueOf(pattern.charAt(i)).toUpperCase();
            int number = SIZE - (findLetterInMatrix(letterToFind, i % 2) + 1);
            setColumn(i, number);
        }
    }

    private int findLetterInMatrix(String letterToFind, int row) {
        for (int i = 0; i < letter[row].length; i++) {
            if (letter[row][i].equals(letterToFind)) {
                return i;
            }
        }
        throw new IllegalArgumentException("The letter '" + letterToFind + "' is not found in the matrix.");
    }
}
