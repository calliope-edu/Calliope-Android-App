package cc.calliope.mini.ui.fragment.web;


import static cc.calliope.mini.core.state.Notification.ERROR;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import cc.calliope.mini.ui.SnackbarHelper;
import cc.calliope.mini.core.service.FlashingService;
import cc.calliope.mini.R;
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
 */
public class WebFragment extends Fragment implements DownloadListener {

    private static final String TAG = "WEB_VIEW";
    private static final String UTF_8 = "UTF-8";
    private static final String TARGET_URL = "editorUrl";
    private static final String TARGET_NAME = "editorName";
    private String editorUrl;
    private String editorName;
    private WebView webView;

    private class JavaScriptInterface {
        private final Context context;

        public JavaScriptInterface(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void getBase64FromBlobData(String url, String name) {
            Log.d(TAG, "Received file: " + name);

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

        /**
         * Handle download from MakeCode controller mode.
         * Called when MakeCode sends postMessage with download data and project name.
         * @param hexData The hex file content as string
         * @param name The project name from MakeCode
         */
        @JavascriptInterface
        public void handleControllerDownload(String hexData, String name) {
            Log.d(TAG, "Controller download: " + name);

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
    }

    public WebFragment() {
        // Required empty public constructor
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
    }

    public int getLayoutId() {
        return R.layout.fragment_web;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(getLayoutId(), container, false);

        webView = view.findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDefaultTextEncodingName("utf-8");

        webView.addJavascriptInterface(new JavaScriptInterface(getContext()), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject download intercept for editors that use blob URLs
                if (url.contains("makecode") || url.contains("python.calliope")) {
                    Log.d(TAG, "Injecting download intercept for: " + url);
                    view.evaluateJavascript(JavaScriptInterface.getDownloadInterceptScript(), null);
                }
            }
        });
        webView.setDownloadListener(this);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState.getBundle("webViewState"));
        } else {
            Log.d(TAG, "Loading URL in WebFragment: " + editorUrl);
            webView.loadUrl(editorUrl);
        }
        return view;
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
                String javaScript = JavaScriptInterface.getBase64StringFromBlobUrl(url, mimetype, fileName);
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

        if (!Utils.isBluetoothEnabled()) {
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
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle bundle = new Bundle();
        webView.saveState(bundle);
        outState.putBundle("webViewState", bundle);
    }
}