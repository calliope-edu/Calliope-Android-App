package cc.calliope.mini;


import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;

import cc.calliope.mini.adapter.ExtendedBluetoothDevice;

public class editorAcitvity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int SDK_INT = android.os.Build.VERSION.SDK_INT;
        if (SDK_INT > 8) {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
                    .permitAll().build();
            StrictMode.setThreadPolicy(policy);
        }

        super.onCreate(savedInstanceState);
        // setContentView(R.layout.activity_editor_acitvity);
        WebView webview = new WebView(this);
        setContentView(webview);

        final Intent intent = getIntent();
        final ExtendedBluetoothDevice device = intent.getParcelableExtra("cc.calliope.mini.EXTRA_DEVICE");
        if (device != null) {
            final String deviceName = device.getName();
            final String deviceAddress = device.getAddress();
            Log.i("DEVICE", deviceName);
            final TextView deviceInfo = findViewById(R.id.deviceInfo);
        }
        Bundle extras = intent.getExtras();
        final String url = extras.getString("TARGET_URL");
        final String editorName = extras.getString("TARGET_NAME");

        webview.setWebChromeClient(new WebChromeClient());
        webview.setWebViewClient(new WebViewClient());
        webview.getSettings().setJavaScriptEnabled(true);

        webview.getSettings().setJavaScriptEnabled(true);

        webview.getSettings().setDomStorageEnabled(true);

        webview.getSettings().setUseWideViewPort(true);
        webview.getSettings().setLoadWithOverviewMode(true);
        webview.getSettings().setDatabaseEnabled(true);

        webview.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(webview, url);
//                Toast.makeText(getApplicationContext(), "Done!", Toast.LENGTH_SHORT).show();
//                webview.loadUrl("javascript:" +
//                        "var x = 0; "+
//                        "checkExist = setInterval(function() { " +
//                        "if (++x === 100) { clearInterval(checkExist); }" +
//                        "        var elements = document.querySelectorAll('[data-type=\"calliope2017\"]');" +
//                        "        if (elements.length) { " +
//                        "            var evObj = document.createEvent('Events');" +
//                        "            evObj.initEvent('click', true, false);" +
//                        "            elements[0].dispatchEvent(evObj);" +
//                        "            elements[1].dispatchEvent(evObj);" +
//                        "            elements[2].dispatchEvent(evObj);" +
//                        "            elements[3].dispatchEvent(evObj);" +
//                        "            clearInterval(checkExist);" +
//                        "" +
//                        "        }" +
//                        "    }, 100);");
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(getApplicationContext(), "Oh no! " + description, Toast.LENGTH_SHORT).show();
            }
        });

        webview.loadUrl(url);


        webview.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype,
                                        long contentLength) {

                Uri uri = Uri.parse(url);
                Log.i("URL", url);
                Log.i("URI", ""+uri);

                //String filetype = url.substring(url.indexOf("/") + 1, url.indexOf(";"));
                String filename = editorName+"-"+System.currentTimeMillis() + ".hex";
                File file = new File(getFilesDir() + File.separator + filename);
                if (file.exists())
                    file.delete();
                try {
                    file.createNewFile();
                }
                catch (Exception e) {
                    e.printStackTrace();
                    Log.w("CreateFile", "Error writing " + file);
                }

                Boolean downloadResult = false;

                if (url.startsWith("blob:")) {  // TODO: BLOB Download
                    Log.i("MODUS", "BLOB");
                    // Can not be parsed
                } else if (url.startsWith("data:text/hex")) {  // when url is base64 encoded data
                    Log.i("MODUS", "HEX");
                    downloadResult = createAndSaveFileFromHexUrl(url, file);
                } else if (url.startsWith("data:") && url.contains("base64")) {  // when url is base64 encoded data
                    Log.i("MODUS", "BASE64");
                    downloadResult = createAndSaveFileFromBase64Url(url, file);
                } else if (URLUtil.isValidUrl(url)) { // real download
                    Log.i("MODUS", "DOWNLOAD");
                    downloadResult = downloadFileFromURL(url, file);
                }

                if(downloadResult && device != null) {
                    final Intent intent = new Intent(editorAcitvity.this, DFUActivity.class);
                    intent.putExtra("cc.calliope.mini.EXTRA_DEVICE", device);
                    intent.putExtra("EXTRA_FILE", file.getAbsolutePath());
                    startActivity(intent);
                } else if(downloadResult) {
                    Toast.makeText(getApplicationContext(), R.string.upload_no_mini_connected, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getApplicationContext(), R.string.download_error, Toast.LENGTH_LONG).show();
                }
            }
        });
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
        Log.i("GESPEICHERT", file.toString());
        return true;
    }


    public Boolean createAndSaveFileFromHexUrl(String url, File file) { // TODO not working yet
        try {
            String hexEncodedString = url.substring(url.indexOf(",") + 1);
            String decodedHex = URLDecoder.decode(hexEncodedString, "utf-8");
            OutputStream os = new FileOutputStream(file);
            try (Writer w = new OutputStreamWriter(os, "UTF-8")) {
                w.write(decodedHex);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        Log.i("GESPEICHERT", file.toString());
        return true;
    }


    public Boolean downloadFileFromURL(final String link, File file) {
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
        }
        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

}
