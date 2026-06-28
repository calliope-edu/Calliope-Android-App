package cc.calliope.mini.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * Helpers for tweaking how dialogs sit above the screen behind them.
 */
public final class WindowUtils {
    /** Gaussian blur radius applied to the content behind a dialog, in dp. */
    private static final int CONTENT_BLUR_RADIUS_DP = 16;

    private WindowUtils() {
    }

    /**
     * Blurs (or, when {@code blurred} is false, clears the blur on) the host
     * activity's content while a dialog is on screen, so the dialog reads as a
     * distinct layer above the now out-of-focus background, on top of the dim.
     *
     * <p>This blurs the activity's own view tree, so unlike cross-window blur
     * (FLAG_BLUR_BEHIND) it does not depend on the system having window blurs
     * enabled and shows on any Android 12 (API 31)+ device. The dialog is a
     * separate window, so it stays crisp. A no-op on older releases (the dim
     * still applies).
     */
    public static void blurBehindDialog(Context context, boolean blurred) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        View content = findContentView(context);
        if (content == null) {
            return;
        }
        if (blurred) {
            float radius = Utils.convertDpToPixel(context, CONTENT_BLUR_RADIUS_DP);
            content.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        } else {
            content.setRenderEffect(null);
        }
    }

    private static View findContentView(Context context) {
        Activity activity = unwrapActivity(context);
        return activity == null ? null : activity.findViewById(android.R.id.content);
    }

    private static Activity unwrapActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
