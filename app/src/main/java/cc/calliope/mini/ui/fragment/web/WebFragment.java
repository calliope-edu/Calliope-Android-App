package cc.calliope.mini.ui.fragment.web;


import static cc.calliope.mini.core.state.Notification.ERROR;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import cc.calliope.mini.ui.SnackbarHelper;
import cc.calliope.mini.core.service.FlashingService;
import cc.calliope.mini.R;
import cc.calliope.mini.scratchlink.ScratchLinkServer;
import cc.calliope.mini.ui.activity.FlashingActivity;
import cc.calliope.mini.core.state.ApplicationStateHandler;
import cc.calliope.mini.utils.settings.Settings;
import cc.calliope.mini.utils.Constants;
import cc.calliope.mini.utils.file.FileUtils;
import cc.calliope.mini.utils.Utils;

import android.os.StrictMode;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;


/**
 * A simple {@link Fragment} subclass.
 * Use the {@link WebFragment#newInstance} factory method to
 * create an instance of this fragment.
 *
 * <p>The WebView showing an editor is retained across view recreation by
 * {@link RetainedWebEditor}, so leaving the editor and coming back keeps the
 * open project and any live BLE session. Subclasses that host a page with no
 * such state (see {@code InfoFragment}) opt out via
 * {@link #isWebViewRetained()} and get a plain, view-scoped WebView.
 */
public class WebFragment extends Fragment implements DownloadListener, HostAccess {

    private static final String TAG = "WEB_VIEW";
    private static final String UTF_8 = "UTF-8";
    private static final String TARGET_URL = "editorUrl";
    private static final String TARGET_NAME = "editorName";
    private static final String STATE_WEB_VIEW = "webViewState";
    private String editorUrl;
    private String editorName;
    private WebView webView;
    private RetainedWebEditor editor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    public static String getBase64StringFromBlobUrl(String blobUrl, String mimeType, String fileName) {
        if (blobUrl.startsWith("blob")) {
            return "javascript: " +
                    "var xhr = new XMLHttpRequest();" +
                    "xhr.open('GET', '" + blobUrl + "', true);" +
                    "xhr.setRequestHeader('Content-type','" + mimeType + ";charset=UTF-8');" +
                    "xhr.responseType = 'blob';" +
                    "xhr.onload = function(e) {" +
                    "    if (this.status == 200) {" +
                    "        var blobFile = this.response;" +
                    "        var name = window.androidLastDownloadName;" +
                    "        if (name) {" +
                    "            name = name.replace(/\\.hex$/i, '').replace(/^mini-/i, '');" +
                    "        } else {" +
                    "            name = blobFile.name;" +
                    "        }" +
                    "        if (!name) {" +
                    "            name = '" + fileName + "';" +
                    "        }" +
                    "        window.androidLastDownloadName = null;" +
                    "        var reader = new FileReader();" +
                    "        reader.readAsDataURL(blobFile);" +
                    "        reader.onloadend = function() {" +
                    "            base64data = reader.result;" +
                    "            Android.getBase64FromBlobData(base64data, name);" +
                    "        }" +
                    "    }" +
                    "};" +
                    "xhr.send();";
        }
        return "javascript: console.log('It is not a Blob URL');";
    }

    /**
     * JavaScript to inject for intercepting download links.
     * This hooks into anchor element clicks and URL.createObjectURL to capture the download filename.
     */
    public static String getDownloadInterceptScript() {
        return "javascript: " +
                "if (!window.androidDownloadInterceptAdded) {" +
                "    window.androidDownloadInterceptAdded = true;" +
                "    window.androidLastDownloadName = null;" +
                "    var originalClick = HTMLAnchorElement.prototype.click;" +
                "    HTMLAnchorElement.prototype.click = function() {" +
                "        if (this.download && this.href && this.href.startsWith('blob:')) {" +
                "            window.androidLastDownloadName = this.download;" +
                "        }" +
                "        return originalClick.apply(this, arguments);" +
                "    };" +
                "    var originalCreateElement = document.createElement.bind(document);" +
                "    document.createElement = function(tag) {" +
                "        var el = originalCreateElement(tag);" +
                "        if (tag.toLowerCase() === 'a') {" +
                "            var desc = Object.getOwnPropertyDescriptor(HTMLAnchorElement.prototype, 'download');" +
                "            Object.defineProperty(el, 'download', {" +
                "                set: function(val) { window.androidLastDownloadName = val; desc.set.call(this, val); }," +
                "                get: function() { return desc.get.call(this); }" +
                "            });" +
                "        }" +
                "        return el;" +
                "    };" +
                "    window.addEventListener('message', function(ev) {" +
                "        var msg = ev.data;" +
                "        if (msg && msg.download && msg.name) {" +
                "            Android.handleControllerDownload(msg.download, msg.name);" +
                "        }" +
                "    }, false);" +
                "}";
    }

    public WebFragment() {
        // Required empty public constructor
    }

    /**
     * CSS injected as early as possible (onPageCommitVisible) into scratch-based
     * editors. The Blocks editor opens its connection modal from its initial
     * redux state — {@code modals.connectionModal} starts {@code true} — so the
     * modal is on screen before {@link #getScratchAutoConnectScript()} can run
     * and close it. This rule keeps that startup modal invisible until the
     * driver script decides its fate (silent connect), avoiding a visible flash;
     * the driver removes the rule so a later user-opened modal shows normally.
     *
     * Scoped to the connection modal's own overlay via :has(), so no other modal
     * (extension library, project loading, …) is affected. Needs Chromium 105+
     * (WebView is updatable, so this holds on essentially all live devices); on
     * anything older the selector is ignored and the modal briefly shows before
     * the driver closes it — i.e. graceful degradation, never a broken editor.
     */
    private static String getConnectionModalHideCss() {
        return "(function(){"
                + "var ID='__calliopeCmHide';"
                + "if(document.getElementById(ID))return;"
                + "var s=document.createElement('style');s.id=ID;"
                + "s.textContent='.ReactModal__Overlay:has([class*=\\\"connection-modal_\\\"]){display:none !important;}';"
                + "(document.head||document.documentElement).appendChild(s);"
                + "})();";
    }

    /**
     * JS injected into scratch-based editors (Blocks) to drive BLE connection
     * from the app side. scratch-vm's io/ble.js already falls back to the
     * Scratch Link protocol when navigator.bluetooth is absent (always, in
     * Android WebView) and connects to our in-app server on ws://127.0.0.1:20111.
     * Two gaps remain, both closed here without any page changes:
     *
     * <p><b>Auto-connect.</b> scratch-gui only routes iPad to its device-picker
     * step; on Android the connection modal is stuck on the intro step and never
     * sends {@code connect}. The driver locates the vm (via the React tree /
     * redux store) and, when a peripheral is discovered during any scan, calls
     * vm.connectPeripheral() with the first result — exactly what Scratch's own
     * AutoScanningStep does. scratch-vm then emits PERIPHERAL_CONNECTED and the
     * extension's status turns green.
     *
     * <p><b>No startup modal.</b> The editor opens the connection modal from its
     * initial redux state, so it pops up on every launch. The driver instead
     * closes it and starts a silent scan, so startup connects in the background.
     * A genuine user tap on the extension status button still opens the modal
     * (detected via a recent input gesture) — that path is left untouched, so
     * the user can always reconnect or disconnect by hand.
     *
     * <p>Note: connects to the FIRST peripheral found (like AutoScanningStep). In
     * a room with several minis this picks the nearest/first to advertise.
     */
    private static String getScratchAutoConnectScript() {
        return """
            (function(){
              if (window.__calliopeConnect) return;
              window.__calliopeConnect = true;
              var TAG = '[CalliopeConnect]';
              var MODAL = 'connectionModal';
              var OPEN_MODAL = 'scratch-gui/modals/OPEN_MODAL';
              var CLOSE_MODAL = 'scratch-gui/modals/CLOSE_MODAL';
              var GESTURE_MS = 2000;
              var DEFAULT_EXT = 'calliopeMini';

              // Track real user input so a modal opened by tapping the status
              // button (manual) is told apart from the automatic startup one.
              var lastGestureAt = 0;
              ['pointerdown','touchstart','mousedown','keydown'].forEach(function(ev){
                document.addEventListener(ev, function(){ lastGestureAt = Date.now(); }, true);
              });
              function gestureRecent(){ return (Date.now() - lastGestureAt) < GESTURE_MS; }

              // The early CSS (getConnectionModalHideCss) hides the startup
              // modal; create it here too in case that injection didn't land,
              // then drop it once startup is handled so manual opens show.
              function ensureHide(){
                if (document.getElementById('__calliopeCmHide')) return;
                var s = document.createElement('style'); s.id = '__calliopeCmHide';
                s.textContent = '.ReactModal__Overlay:has([class*="connection-modal_"]){display:none !important;}';
                (document.head || document.documentElement).appendChild(s);
              }
              function revealModal(){
                var s = document.getElementById('__calliopeCmHide');
                if (s && s.parentNode) s.parentNode.removeChild(s);
              }
              ensureHide();

              function isVM(o){
                try { return o && typeof o.connectPeripheral==='function'
                  && typeof o.scanForPeripheral==='function'
                  && typeof o.on==='function'; } catch(e){ return false; }
              }
              function findVMandStore(){
                try {
                  var nodes = document.querySelectorAll('*'), anyFiber = null;
                  for (var i=0; i<nodes.length && i<4000; i++){
                    var el = nodes[i];
                    var k = Object.keys(el).find(function(x){
                      return x.indexOf('__reactFiber$')===0 || x.indexOf('__reactInternalInstance$')===0; });
                    if (k){ anyFiber = el[k]; break; }
                  }
                  if (!anyFiber) return null;
                  var root = anyFiber, g = 0;
                  while (root.return && g++ < 5000) root = root.return;
                  var stack = [root], seen = new Set(), visited = 0, store = null, vm = null;
                  while (stack.length && visited < 60000){
                    var f = stack.pop(); if (!f || seen.has(f)) continue; seen.add(f); visited++;
                    var mp = f.memoizedProps, ms = f.memoizedState;
                    if (mp){ if (isVM(mp.vm)) vm = mp.vm;
                      if (mp.store && typeof mp.store.getState==='function') store = mp.store; }
                    if (ms && isVM(ms.vm)) vm = ms.vm;
                    if (f.child) stack.push(f.child);
                    if (f.sibling) stack.push(f.sibling);
                  }
                  if (!vm && store){ try { var v = store.getState().scratchGui.vm; if (isVM(v)) vm = v; } catch(e){} }
                  if (vm && store) return { vm: vm, store: store };
                } catch(e){}
                return null;
              }

              function modalOpen(store){
                try { return !!store.getState().scratchGui.modals[MODAL]; } catch(e){ return false; }
              }
              function extIdOf(store){
                try { return store.getState().scratchGui.connectionModal.extensionId || DEFAULT_EXT; }
                catch(e){ return DEFAULT_EXT; }
              }
              function isConnected(vm, extId){
                try { return !!(extId && vm.getPeripheralIsConnected(extId)); } catch(e){ return false; }
              }

              function install(vm, store){
                window.__calliopeVM = vm;
                var currentExt = null, connecting = false;

                // Auto-connect to the first peripheral seen during any scan —
                // whether we start it silently at launch or the user starts it
                // from the modal's Connect button.
                var origScan = vm.scanForPeripheral.bind(vm);
                vm.scanForPeripheral = function(extId){ currentExt = extId; connecting = false; return origScan(extId); };
                vm.on('PERIPHERAL_LIST_UPDATE', function(list){
                  if (connecting || !currentExt || !list) return;
                  if (isConnected(vm, currentExt)) return;
                  var ids = Object.keys(list); if (!ids.length) return;
                  var p = list[ids[0]]; if (!p || !p.peripheralId) return;
                  connecting = true;
                  console.log(TAG, 'auto-connecting', currentExt, p.peripheralId, p.name);
                  try { vm.connectPeripheral(currentExt, p.peripheralId); } catch(e){ connecting = false; }
                });
                vm.on('PERIPHERAL_CONNECTED', function(){ connecting = false; console.log(TAG, 'connected'); });
                vm.on('PERIPHERAL_REQUEST_ERROR', function(){ connecting = false; console.log(TAG, 'request error'); });
                vm.on('PERIPHERAL_SCAN_TIMEOUT', function(){ connecting = false; });

                function startSilentScan(){
                  var extId = extIdOf(store);
                  if (isConnected(vm, extId)) return;
                  // Small delay so a just-closed modal (iPad's scanning phase)
                  // finishes tearing down before we (re)start the scan.
                  setTimeout(function(){ try { vm.scanForPeripheral(extId); } catch(e){} }, 200);
                }

                // Startup: the modal is open from initial state. Untouched by
                // the user -> close it and connect in the background.
                if (modalOpen(store) && !gestureRecent()){
                  console.log(TAG, 'suppressing startup modal, connecting silently');
                  store.dispatch({ type: CLOSE_MODAL, modal: MODAL });
                  startSilentScan();
                }
                revealModal();

                // After startup the only opener is the user tapping the status
                // button — let those through. Anything that opens with no recent
                // gesture is closed and handled as a silent (re)connect.
                var prevOpen = modalOpen(store);
                store.subscribe(function(){
                  var open = modalOpen(store);
                  if (open && !prevOpen && !gestureRecent()){
                    store.dispatch({ type: CLOSE_MODAL, modal: MODAL });
                    startSilentScan();
                  }
                  prevOpen = open;
                });

                console.log(TAG, 'installed');
              }

              var tries = 0;
              var timer = setInterval(function(){
                var found = findVMandStore();
                if (found){ clearInterval(timer); install(found.vm, found.store); }
                else if (++tries > 60){ clearInterval(timer); revealModal(); console.log(TAG, 'vm/store not found'); }
              }, 500);
            })();
            """;
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param editorName Editor name.
     * @param url        Editor URL.
     * @return A new instance of fragment WebFragment.
     */

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

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

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

    public int getLayoutId() {
        return R.layout.fragment_web;
    }

    /**
     * Whether this fragment's WebView should survive view recreation. True for
     * editors, whose page holds the user's project and any live BLE session;
     * subclasses showing a stateless page override it to false and get a
     * WebView inflated from their own layout instead.
     */
    protected boolean isWebViewRetained() {
        return true;
    }

    /**
     * Applies the settings every editor page needs and binds the JavaScript
     * bridge. Called once per WebView — including for the retained one, which
     * is why the bridge talks to {@code hostAccess} instead of a fragment.
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
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(getLayoutId(), container, false);

        // Allow inspecting the editor's WebView via chrome://inspect in debug
        // builds only (no-op in release).
        if (cc.calliope.mini.BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        boolean alreadyLoaded = false;
        if (isWebViewRetained()) {
            ViewGroup webViewContainer = view.findViewById(R.id.webViewContainer);
            editor = RetainedWebEditor.acquire(requireActivity(), editorUrl);
            webView = editor.getWebView();
            alreadyLoaded = editor.isLoaded();
            editor.attach(this, webViewContainer);
        } else {
            webView = view.findViewById(R.id.webView);
            configureWebView(webView, this);
        }

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
            if (editor != null) {
                editor.markLoaded();
            }
        }
        return view;
    }

    private WebViewClient createWebViewClient() {
        return new WebViewClient() {
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Only show error for main page, not for sub-resources
                if (request.isForMainFrame()) {
                    Log.e(TAG, "Main page error: " + error.getDescription() + " for URL: " + request.getUrl());
                    SnackbarHelper.errorSnackbar(webView, String.format(getString(R.string.web_error_oh_no), error.getDescription())).show();
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
                // it from its initial state, so the driver can dismiss it
                // (below) without the user seeing a flash. Injected this early
                // because onPageFinished is already too late — React has mounted
                // the modal by then.
                if (ScratchLinkServer.isScratchEditorUrl(url)) {
                    view.evaluateJavascript(getConnectionModalHideCss(), null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject download intercept for editors that use blob URLs
                if (url.contains("makecode") || url.contains("python.calliope")) {
                    Log.d(TAG, "Injecting download intercept for: " + url);
                    view.evaluateJavascript(getDownloadInterceptScript(), null);
                }
                // Scratch-based editors (Blocks): drive scratch-vm to auto-connect
                // to the first peripheral in the background and suppress the
                // startup connection modal, while leaving a user-tapped modal
                // open — no scratch-gui changes required.
                if (ScratchLinkServer.isScratchEditorUrl(url)) {
                    Log.d(TAG, "Injecting Scratch auto-connect for: " + url);
                    view.evaluateJavascript(getScratchAutoConnectScript(), null);
                }
            }
        };
    }

    @Override
    public void runOnHost(@NonNull HostAccess.HostAction action) {
        // Editor callbacks arrive on the JavaScript bridge thread; hop to the
        // main thread and drop anything that lands after the view is gone.
        mainHandler.post(() -> {
            if (isAdded() && webView != null) {
                action.run(this);
            } else {
                Log.w(TAG, "dropping editor callback: view is gone");
            }
        });
    }

    /** A blob download finished in the page and arrived as a data URL. */
    void onBlobDownload(String url, String name) {
        Log.d(TAG, "Received file: " + name);

        Context context = getContext();
        if (context == null) {
            return;
        }
        File file = FileUtils.getFile(context, editorName, name);
        if (file == null) {
            SnackbarHelper.errorSnackbar(webView, getString(R.string.error_snackbar_save_file_error)).show();
        } else {
            if (createAndSaveFileFromBase64Url(url, file)) {
                startDfuActivity(file);
            } else {
                SnackbarHelper.errorSnackbar(webView, getString(R.string.error_snackbar_download_error)).show();
            }
        }
    }

    /** MakeCode controller mode posted a hex payload and its project name. */
    void onControllerDownload(String hexData, String name) {
        Log.d(TAG, "Controller download: " + name);

        Context context = getContext();
        if (context == null) {
            return;
        }
        String fileName = cleanFileName(name);
        File file = FileUtils.getFile(context, editorName, fileName);
        if (file == null) {
            SnackbarHelper.errorSnackbar(webView, getString(R.string.error_snackbar_save_file_error)).show();
        } else {
            if (saveHexFile(hexData, file)) {
                startDfuActivity(file);
            } else {
                SnackbarHelper.errorSnackbar(webView, getString(R.string.error_snackbar_download_error)).show();
            }
        }
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
                String javaScript = getBase64StringFromBlobUrl(url, mimetype, fileName);
                webView.loadUrl(javaScript);
            } else {
                selectDownloadMethod(decodedUrl);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
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

    private void selectDownloadMethod(String url) {
        Context context = getContext();
        if (context == null) {
            return;
        }

        String name = FileUtils.getFileName(url);
        File file = FileUtils.getFile(context, editorName, name);
        boolean result = false;

        if (file == null) {
            Log.e(TAG, "File is null");
            SnackbarHelper.errorSnackbar(webView, getString(R.string.error_snackbar_save_file_error)).show();
        } else {
            if (url.startsWith("data:text/hex")) {
                result = createAndSaveFileFromHexUrl(url, file);
            } else if (url.startsWith("data:") && url.contains("base64")) {
                result = createAndSaveFileFromBase64Url(url, file);
            } else if (URLUtil.isValidUrl(url) && url.endsWith(".hex")) {
                result = downloadFileFromURL(url, file);
            }
            if (result) {
                startDfuActivity(file);
            } else {
                SnackbarHelper.errorSnackbar(webView, getString(R.string.error_snackbar_download_error)).show();
            }
        }
    }

    private boolean saveHexFile(String hexData, File file) {
        if (hexData == null || hexData.isEmpty()) {
            Log.e(TAG, "saveHexFile: hexData is null or empty");
            return false;
        }
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(hexData.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "saveHexFile: " + file.toString());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean createAndSaveFileFromHexUrl(String url, File file) {
        try {
            String hexEncodedString = url.substring(url.indexOf(",") + 1);
            OutputStream outputStream = new FileOutputStream(file);
            try (Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                writer.write(hexEncodedString);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        Log.i(TAG, "createAndSaveFileFromHexUrl: " + file.toString());
        return true;
    }

    public Boolean createAndSaveFileFromBase64Url(String url, File file) {
        try {
            String base64EncodedString = url.substring(url.indexOf(",") + 1);
            byte[] decodedBytes = Base64.decode(base64EncodedString, Base64.DEFAULT);
            OutputStream os = new FileOutputStream(file);
            os.write(decodedBytes);
            os.close();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        Log.i(TAG, "createAndSaveFileFromBase64Url: " + file.toString());
        return true;
    }


    public Boolean downloadFileFromURL(String link, File file) {
        try {
            URL url = new URL(link);
            URLConnection ucon = url.openConnection();
            ucon.setReadTimeout(5000);
            ucon.setConnectTimeout(10000);

            InputStream is = ucon.getInputStream();
            BufferedInputStream inStream = new BufferedInputStream(is, 1024 * 5);
            FileOutputStream outStream = new FileOutputStream(file);
            byte[] buff = new byte[5 * 1024];
            int len;
            while ((len = inStream.read(buff)) != -1) {
                outStream.write(buff, 0, len);
            }
            outStream.flush();
            outStream.close();
            inStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        Log.i(TAG, "downloadFileFromURL: " + file.toString());
        return true;
    }

    private void startDfuActivity(File file) {
        boolean autoFlashing = Settings.isAutoFlashingEnable(getContext());
        if (!autoFlashing) {
            return;
        }

        if (!Utils.isBluetoothEnabled(requireContext())) {
            ApplicationStateHandler.updateNotification(ERROR, getString(R.string.error_snackbar_bluetooth_disabled));
            return;
        }

        if (ApplicationStateHandler.getDeviceAvailabilityLiveData().getValue() == null || !ApplicationStateHandler.getDeviceAvailabilityLiveData().getValue()) {
            ApplicationStateHandler.updateNotification(ERROR, R.string.error_no_connected);
            return;
        }

        if (!Settings.isBackgroundFlashingEnable(getActivity())) {
            final Intent intent = new Intent(getActivity(), FlashingActivity.class);
            intent.putExtra(Constants.EXTRA_FILE_PATH, file.getAbsolutePath());
            startActivity(intent);
        }

        Intent serviceIntent = new Intent(getActivity(), FlashingService.class);
        serviceIntent.putExtra(Constants.EXTRA_FILE_PATH, file.getAbsolutePath());
        getActivity().startService(serviceIntent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (editor != null) {
            // Keep the page (and its BLE session) running for the next visit.
            editor.detach(this);
            editor = null;
        } else if (webView != null) {
            webView.setDownloadListener(null);
            webView.setWebViewClient(new WebViewClient());
            // destroy() requires the WebView to be out of the view system.
            if (webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            webView.destroy();
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
