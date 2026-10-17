package cc.calliope.mini.ui.fragment.web;

import androidx.annotation.NonNull;

/**
 * How a retained WebView's long-lived callbacks reach a fragment. A retained
 * WebView outlives any single fragment, so its JavaScript bridge can't hold
 * one: it asks for the current host instead, on the main thread.
 *
 * <p>Implemented by {@link RetainedWebEditor}, which knows which fragment —
 * if any — is showing its WebView right now.
 */
public interface HostAccess {

    /** Something to run against the fragment currently hosting a WebView. */
    interface HostAction {
        void run(@NonNull WebFragment host);
    }

    void runOnHost(@NonNull HostAction action);
}
