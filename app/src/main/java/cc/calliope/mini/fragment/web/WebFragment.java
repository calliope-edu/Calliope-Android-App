package cc.calliope.mini.fragment.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import cc.calliope.mini.FlashingService;
import cc.calliope.mini.R;
import cc.calliope.mini.activity.FlashingActivity;
import cc.calliope.mini.utils.Settings;
import cc.calliope.mini.utils.StaticExtras;
import cc.calliope.mini.utils.FileUtils;
import cc.calliope.mini.utils.Utils;
import cc.calliope.mini.utils.Version;

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
            Log.d(TAG, "base64Data: " + url);
            Log.d(TAG, "name: " + name);

            File file = FileUtils.getFile(context, editorName, name);
            if (file == null) {
                Utils.errorSnackbar(webView, getString(R.string.error_snackbar_save_file_error)).show();
            } else {
                if (createAndSaveFileFromBase64Url(url, file)) {
                    startDfuActivity(file);
                } else {
                    Utils.errorSnackbar(webView, getString(R.string.error_snackbar_download_error)).show();
                }
            }
        }

        public static String getBase64StringFromBlobUrl(String blobUrl, String mimeType) {
            if (blobUrl.startsWith("blob")) {
                return "javascript: " +
                        "var xhr = new XMLHttpRequest();" +
                        "xhr.open('GET', '" + blobUrl + "', true);" +
                        "xhr.setRequestHeader('Content-type','" + mimeType + ";charset=UTF-8');" +
                        "xhr.responseType = 'blob';" +
                        "xhr.onload = function(e) {" +
                        "    if (this.status == 200) {" +
                        "        var blobFile = this.response;" +
                        "        var name = blobFile.name;" +
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

        Utils.log(Log.ASSERT, TAG, "onCreate");

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        Bundle arguments = getArguments();
        if (arguments != null) {
            editorUrl = arguments.getString(TARGET_URL);
            editorName = arguments.getString(TARGET_NAME);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_web, container, false);

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
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Version.VERSION_M_AND_NEWER) {
                    Utils.errorSnackbar(webView, "Oh no! " + error.getDescription()).show();
                } else {
                    Utils.errorSnackbar(webView, "Oh no! onReceivedError").show();
                }
            }
        });
        webView.setDownloadListener(this);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState.getBundle("webViewState"));
        } else {
            webView.loadUrl(editorUrl);
        }
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    //TODO завантажувати xml і ділитися ними

    @Override
    public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        Log.i(TAG, "editorName: " + editorName);
        Log.i(TAG, "URL: " + url);
        Log.i(TAG, "userAgent: " + userAgent);
        Log.i(TAG, "contentDisposition: " + contentDisposition);
        Log.i(TAG, "mimetype: " + mimetype);
        Log.i(TAG, "contentLength: " + contentLength);

        try {
            String decodedUrl = URLDecoder.decode(url, UTF_8);
            if (decodedUrl.startsWith("blob:")) {
                String javaScript = JavaScriptInterface.getBase64StringFromBlobUrl(url, mimetype);
                Log.v(TAG, "javaScript: " + javaScript);
                webView.loadUrl(javaScript);
            } else {
                selectDownloadMethod(decodedUrl);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
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
            Utils.errorSnackbar(webView, getString(R.string.error_snackbar_save_file_error)).show();
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
                Utils.errorSnackbar(webView, getString(R.string.error_snackbar_download_error)).show();
            }
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

        final Intent intent = new Intent(getActivity(), FlashingActivity.class);
        //intent.putExtra(StaticExtra.EXTRA_FILE_PATH, file.getAbsolutePath());
        startActivity(intent);

        Intent serviceIntent = new Intent(getActivity(), FlashingService.class);
        serviceIntent.putExtra(StaticExtras.EXTRA_FILE_PATH, file.getAbsolutePath());
        getActivity().startService(serviceIntent);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Utils.log(Log.ASSERT, TAG, "onSaveInstanceState");
        Bundle bundle = new Bundle();
        webView.saveState(bundle);
        outState.putBundle("webViewState", bundle);
    }
}