package cc.calliope.mini.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.NotNull;

import cc.calliope.mini.R;
import cc.calliope.mini.utils.Utils;

public class SnackbarHelper {

    public static Snackbar infoSnackbar(View view, @NotNull String message) {
        return baseSnackbar(view, message, R.color.aqua_500);
    }

    public static Snackbar warningSnackbar(View view, @NotNull String message) {
        return baseSnackbar(view, message, R.color.yellow_500);
    }

    public static Snackbar errorSnackbar(View view, @NotNull String message) {
        return baseSnackbar(view, message, R.color.red);
    }

    private static Snackbar baseSnackbar(View view, @NotNull String message, int color) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = Utils.convertDpToPixel(view.getContext(), 8);

        Snackbar snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG);
        snackbar.getView().setBackgroundTintList(ContextCompat.getColorStateList(view.getContext(), color));
        snackbar.getView().setLayoutParams(params);
        return snackbar;
    }
}