package cc.calliope.mini.ui.fragment.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import cc.calliope.mini.BuildConfig;
import cc.calliope.mini.R;
import cc.calliope.mini.core.service.FlashLauncher;
import cc.calliope.mini.scratchlink.ScratchLinkServer;
import cc.calliope.mini.ui.SnackbarHelper;
import cc.calliope.mini.utils.settings.Settings;

/**
 * Hosts a web editor (MakeCode, Python, Blocks, …).
 *
 * <p>The WebView is retained across view recreation by
 * {@link RetainedWebEditor}, so leaving the editor and coming back keeps the
 * open project and any live BLE session. The fragment itself is glue: it
 * injects the page scripts ({@link WebScripts}), routes downloads to
 * {@link EditorDownloadHandler} and starts a flash through
 * {@link FlashLauncher}.
 */
public class WebFragment extends Fragment implements DownloadListener {

    private static final String TAG = "WEB_VIEW";
    private static final String UTF_8 = "UTF-8";
    private static final String TARGET_URL = "editorUrl";
    private static final String TARGET_NAME = "editorName";
    private static final String STATE_WEB_VIEW = "webViewState";
    private String editorUrl;
    private String editorName;
    private WebView webView;
    private RetainedWebEditor editor;
    private EditorDownloadHandler downloads;

    /**
     * The {@code Android} object the editors call into. Bound to the WebView
     * rather than to a fragment, because replacing a JavaScript interface
     * only takes effect on the next page load — the very thing retaining the
     * page avoids.
     */
    private static class JavaScriptInterface {
        private final HostAccess hostAccess;

        JavaScriptInterface(HostAccess hostAccess) {
            this.hostAccess = hostAccess;
        }

        @JavascriptInterface
        public void getBase64FromBlobData(String url, String name) {
            hostAccess.runOnHost(host -> host.onBlobDownload(url, name));
        }

        /**
         * Handle download from MakeCode controller mode.
         * Called when MakeCode sends postMessage with download data and project name.
         */
        @JavascriptInterface
        public void handleControllerDownload(String hexData, String name) {
            hostAccess.runOnHost(host -> host.onControllerDownload(hexData, name));
        }
    }

    public WebFragment() {
        // Required empty public constructor
    }

    public static WebFragment newInstance(@NonNull String url, @NonNull String editorName) {
        WebFragment fragment = new WebFragment();
        Bundle args = new Bundle();
        args.putString(TARGET_URL, url);
        args.putString(TARGET_NAME, editorName);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle arguments = getArguments();
        if (arguments != null) {
            editorUrl = arguments.getString(TARGET_URL);
            editorName = arguments.getString(TARGET_NAME);
        }
        Log.d(TAG, "WebFragment created for editor: " + editorName + ", URL: " + editorUrl);

        // Scratch-based editors (Blocks) drive BLE through the Scratch Link
        // protocol when navigator.bluetooth is unavailable. Start the loopback
        // server only for those editors; it is idempotent and lives for the
        // process, so non-scratch editors never open the port.
        if (ScratchLinkServer.isScratchEditorUrl(editorUrl)) {
            ScratchLinkServer.start(requireContext());
        }
    }

    /**
     * Applies the settings every editor page needs and binds the JavaScript
     * bridge. Called once per WebView — the retained one — which is why the
     * bridge talks to {@code hostAccess} instead of a fragment.
     */
    @SuppressLint("SetJavaScriptEnabled")
    static void configureWebView(@NonNull WebView webView, @NonNull HostAccess hostAccess) {
        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDefaultTextEncodingName("utf-8");

        webView.addJavascriptInterface(new JavaScriptInterface(hostAccess), "Android");
        webView.setWebChromeClient(new WebChromeClient());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_web, container, false);

        // Allow inspecting the editor's WebView via chrome://inspect in debug
        // builds only (no-op in release).
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        downloads = new EditorDownloadHandler(requireContext(), editorName);

        ViewGroup webViewContainer = view.findViewById(R.id.webViewContainer);
        editor = RetainedWebEditor.acquire(requireActivity(), editorUrl);
        webView = editor.getWebView();
        boolean alreadyLoaded = editor.isLoaded();
        editor.attach(this, webViewContainer);

        // Rebound on every attach: these callbacks reference this fragment, so
        // they must not outlive it (detach()/onDestroyView() clear them).
        webView.setWebViewClient(createWebViewClient());
        webView.setDownloadListener(this);

        if (!alreadyLoaded) {
            // Only reached for a WebView with no page yet. A saved bundle is
            // present when the process was killed and rebuilt; it restores the
            // navigation history, not the page state, so it is a fallback
            // rather than the mechanism that keeps the project alive.
            Bundle webViewState = savedInstanceState != null
                    ? savedInstanceState.getBundle(STATE_WEB_VIEW) : null;
            if (webViewState == null || webView.restoreState(webViewState) == null) {
                Log.d(TAG, "Loading URL in WebFragment: " + editorUrl);
                webView.loadUrl(editorUrl);
            }
            editor.markLoaded();
        }
        return view;
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Only show error for main page, not for sub-resources
                if (request.isForMainFrame()) {
                    Log.e(TAG, "Main page error: " + error.getDescription() + " for URL: " + request.getUrl());
                    showError(String.format(getString(R.string.web_error_oh_no), error.getDescription()));
                } else {
                    // Log sub-resource errors but don't show to user
                    Log.d(TAG, "Sub-resource error: " + error.getDescription() + " for URL: " + request.getUrl());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                // Handle HTTP errors (4xx, 5xx)
                if (request.isForMainFrame()) {
                    Log.e(TAG, "HTTP Error: " + errorResponse.getStatusCode() + " for URL: " + request.getUrl());
                } else {
                    Log.d(TAG, "Sub-resource HTTP error: " + errorResponse.getStatusCode() + " for URL: " + request.getUrl());
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                // Hide the Blocks connection modal before scratch-gui renders
                // it from its initial state (modals.connectionModal starts
                // true), so the auto-connect driver can dismiss it without the
                // user seeing a flash. onPageFinished is already too late —
                // React has mounted the modal by then. The rule is scoped to
                // the connection modal's own overlay via :has() (Chromium
                // 105+); on anything older it is ignored and the modal briefly
                // shows before the driver closes it.
                if (ScratchLinkServer.isScratchEditorUrl(url)) {
                    inject(view, WebScripts.CONNECTION_MODAL_HIDE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject download intercept for editors that use blob URLs
                if (url.contains("makecode") || url.contains("python.calliope")) {
                    Log.d(TAG, "Injecting download intercept for: " + url);
                    inject(view, WebScripts.DOWNLOAD_INTERCEPT);
                }
                // Scratch-based editors (Blocks): drive scratch-vm to auto-connect
                // to the first peripheral in the background and suppress the
                // startup connection modal, while leaving a user-tapped modal
                // open — no scratch-gui changes required. Note: it connects to
                // the FIRST peripheral found (like Scratch's own
                // AutoScanningStep); in a room with several minis that is the
                // nearest/first to advertise.
                if (ScratchLinkServer.isScratchEditorUrl(url)) {
                    Log.d(TAG, "Injecting Scratch auto-connect for: " + url);
                    inject(view, WebScripts.SCRATCH_AUTO_CONNECT);
                }
            }
        };
    }

    private static void inject(WebView view, String scriptName) {
        view.evaluateJavascript(WebScripts.load(view.getContext(), scriptName), null);
    }

    // ---- Downloads ---------------------------------------------------------

    /** Saved on a worker thread; on success the program is flashed if the user wants that. */
    private final EditorDownloadHandler.Callback downloadCallback = new EditorDownloadHandler.Callback() {
        @Override
        public void onSaved(@NonNull File file) {
            Context context = getContext();
            if (context != null && Settings.isAutoFlashingEnable(context)) {
                FlashLauncher.launch(requireActivity(), file.getAbsolutePath());
            }
        }

        @Override
        public void onFailed(int messageRes) {
            if (isAdded()) {
                showError(getString(messageRes));
            }
        }
    };

    /** A blob download finished in the page and arrived as a data URL. */
    void onBlobDownload(String url, String name) {
        Log.d(TAG, "Received file: " + name);
        downloads.saveBase64DataUrl(url, name, downloadCallback);
    }

    /** MakeCode controller mode posted a hex payload and its project name. */
    void onControllerDownload(String hexData, String name) {
        Log.d(TAG, "Controller download: " + name);
        downloads.saveHexText(hexData, cleanFileName(name), downloadCallback);
    }

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        Log.d(TAG, "Download started: " + mimetype + ", size: " + contentLength);

        try {
            String decodedUrl = URLDecoder.decode(url, UTF_8);
            if (decodedUrl.startsWith("blob:")) {
                String fileName = getFileNameFromContentDisposition(contentDisposition);
                if (fileName == null || fileName.isEmpty()) {
                    // Fallback: generate name based on editor and timestamp
                    fileName = editorName + "_" + System.currentTimeMillis();
                } else {
                    // Clean up filename (remove .hex extension and mini- prefix)
                    fileName = cleanFileName(fileName);
                }
                Log.d(TAG, "Resolved fileName: " + fileName);
                // The page reads its own blob and calls back into
                // Android.getBase64FromBlobData (see onBlobDownload).
                webView.evaluateJavascript(
                        WebScripts.blobToBase64(requireContext(), url, mimetype, fileName), null);
            } else {
                downloads.saveFromUrl(decodedUrl, downloadCallback);
            }
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "Could not decode download url", e);
        }
    }

    private String getFileNameFromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) {
            return null;
        }
        // Parse filename="..." or filename=...
        String[] parts = contentDisposition.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("filename=")) {
                String filename = trimmed.substring(9).trim();
                // Remove quotes if present
                if (filename.startsWith("\"") && filename.endsWith("\"")) {
                    filename = filename.substring(1, filename.length() - 1);
                }
                // Handle URL-encoded filenames
                try {
                    filename = URLDecoder.decode(filename, UTF_8);
                } catch (UnsupportedEncodingException e) {
                    Log.w(TAG, "Failed to decode filename: " + filename);
                }
                return filename;
            }
        }
        return null;
    }

    private String cleanFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        // Remove .hex extension
        if (fileName.toLowerCase().endsWith(".hex")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        // Remove mini- prefix (added by MakeCode)
        if (fileName.toLowerCase().startsWith("mini-")) {
            fileName = fileName.substring(5);
        }
        return fileName;
    }

    private void showError(String message) {
        if (webView != null) {
            SnackbarHelper.errorSnackbar(webView, message).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (downloads != null) {
            downloads.close();
            downloads = null;
        }
        if (editor != null) {
            // Keep the page (and its BLE session) running for the next visit.
            editor.detach(this);
            editor = null;
        }
        webView = null;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        WebView view = webView;
        if (view == null || (editor != null && editor.isDestroyed())) {
            return;
        }
        Bundle bundle = new Bundle();
        view.saveState(bundle);
        outState.putBundle(STATE_WEB_VIEW, bundle);
    }
}
