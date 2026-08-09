package cc.calliope.mini.ui.fragment.web;

import android.app.Activity;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cc.calliope.mini.scratchlink.ScratchLinkServer;

/**
 * The WebView of one editor, kept alive across fragment view recreation.
 *
 * Leaving an editor — bottom nav to Settings, or back to the editors list —
 * tears down the fragment's view, and returning builds a new one. A WebView
 * created from scratch that way starts from a fresh page load, which costs
 * the user everything the page was holding: the project open in the editor,
 * and, for Blocks, the live Scratch Link session too. That session is the
 * worse half. It only ends when the old WebView's WebSocket to the in-app
 * server closes, and an orphaned WebView never closes it, so the previous
 * page keeps the peripheral connected while the new page — unable to see a
 * device that no longer advertises — is stuck asking the user to connect.
 *
 * {@code WebView.saveState()} cannot fix this: it round-trips the navigation
 * history, never the DOM or the JS heap. Retaining the WebView object itself
 * does, and as a side effect the BLE session simply never drops.
 *
 * The WebView is built over a {@link MutableContextWrapper} so it can be
 * re-based onto whichever activity currently hosts it rather than pinning
 * the first one. Only one editor is retained at a time; opening a different
 * URL destroys the previous one and releases its BLE session.
 *
 * Main thread only.
 */
public final class RetainedWebEditor implements HostAccess {

    private static final String TAG = "RetainedWebEditor";

    @Nullable
    private static RetainedWebEditor current;

    private final Context appContext;
    private final MutableContextWrapper contextWrapper;
    private final WebView webView;
    private final String url;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private WebFragment host;
    private boolean loaded;
    private boolean destroyed;

    private RetainedWebEditor(@NonNull Activity activity, @NonNull String url) {
        this.appContext = activity.getApplicationContext();
        this.url = url;
        this.contextWrapper = new MutableContextWrapper(activity);
        this.webView = new WebView(contextWrapper);
    }

    /**
     * The retained editor for {@code url}, creating it on first use. Opening
     * a different URL discards the previous editor first — one page at a
     * time, so a stale one can never hold the radio.
     */
    @NonNull
    static RetainedWebEditor acquire(@NonNull Activity activity, @NonNull String url) {
        RetainedWebEditor editor = current;
        if (editor != null && !editor.url.equals(url)) {
            editor.destroy("opening a different editor");
            editor = null;
        }
        if (editor == null) {
            editor = new RetainedWebEditor(activity, url);
            WebFragment.configureWebView(editor.webView, editor);
            current = editor;
            Log.i(TAG, "retaining editor: " + url);
        }
        return editor;
    }

    @NonNull
    WebView getWebView() {
        return webView;
    }

    /** True once the page has been loaded — a re-attach must not reload it. */
    boolean isLoaded() {
        return loaded;
    }

    /**
     * True once the WebView has been destroyed. A fragment on its way out can
     * still be holding this editor — navigation may build the incoming
     * fragment before the outgoing one tears its view down — and no WebView
     * method may be called after destroy().
     */
    boolean isDestroyed() {
        return destroyed;
    }

    void markLoaded() {
        loaded = true;
    }

    /** Moves the WebView into {@code container} and adopts its activity. */
    void attach(@NonNull WebFragment fragment, @NonNull ViewGroup container) {
        host = fragment;
        contextWrapper.setBaseContext(fragment.requireActivity());
        removeFromParent();
        container.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Takes the WebView off screen and drops every reference that would
     * otherwise pin the departing fragment or its activity. The page itself
     * keeps running, which is the whole point.
     */
    void detach(@NonNull WebFragment fragment) {
        if (host != fragment) {
            return;
        }
        host = null;
        removeFromParent();
        webView.setWebViewClient(new WebViewClient());
        webView.setDownloadListener(null);
        contextWrapper.setBaseContext(appContext);
    }

    @Override
    public void runOnHost(@NonNull HostAccess.HostAction action) {
        mainHandler.post(() -> {
            WebFragment fragment = host;
            if (fragment != null && fragment.isAdded()) {
                action.run(fragment);
            } else {
                Log.w(TAG, "dropping editor callback: no fragment attached");
            }
        });
    }

    /** Frees the retained editor whatever its state — the app is going away. */
    public static void destroyAll(@NonNull String reason) {
        RetainedWebEditor editor = current;
        if (editor != null) {
            editor.destroy(reason);
        }
    }

    /** Frees the retained editor only while it is off screen. */
    public static void destroyIfDetached(@NonNull String reason) {
        RetainedWebEditor editor = current;
        if (editor != null && editor.host == null) {
            editor.destroy(reason);
        }
    }

    private void destroy(@NonNull String reason) {
        if (destroyed) {
            return;
        }
        destroyed = true;
        Log.i(TAG, "destroying retained editor (" + reason + "): " + url);
        host = null;
        removeFromParent();
        webView.stopLoading();
        webView.setWebViewClient(new WebViewClient());
        webView.setDownloadListener(null);
        webView.destroy();
        contextWrapper.setBaseContext(appContext);
        if (current == this) {
            current = null;
        }
        // Destroying the WebView closes its WebSocket, which is what releases
        // the peripheral. Say it explicitly too, so a socket that somehow
        // outlives its page can never keep the radio — and the FAB's
        // connected state — hostage.
        ScratchLinkServer.closeAllSessions(reason);
    }

    private void removeFromParent() {
        ViewParent parent = webView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(webView);
        }
    }
}
