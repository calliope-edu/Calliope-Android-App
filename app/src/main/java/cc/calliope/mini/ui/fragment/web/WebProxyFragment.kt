package cc.calliope.mini.ui.fragment.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cc.calliope.mini.bridge.BridgeController
import cc.calliope.mini.bridge.CalliopeProxyBridge

/**
 * WebView host for the Calliope-Campus native-proxy editor.
 *
 * Loads a campus URL in a WebView, injects the `CalliopeNative` JS bridge,
 * and lets a [BridgeController] handle every connect / GATT / flash request
 * the embedded `@calliope-edu/mini-connection-widget` posts back.
 *
 * Unlike [WebFragment] (legacy editors, download-capture) and [WebBleFragment]
 * (Cardboard UART widget), this fragment:
 *   - never installs a download listener — flash flows entirely through the
 *     bridge, no hex blob ever leaves JS;
 *   - never bonds the BLE device — the campus widget uses CODAL open mode;
 *   - never injects editor-specific shims (no `window.sendUART`, etc.).
 */
class WebProxyFragment : Fragment() {

    private var pageUrl: String = ""
    private var editorName: String? = null

    companion object {
        private const val TAG = "WebProxyFragment"
        private const val ARG_URL = "editorUrl"
        private const val ARG_NAME = "editorName"

        fun newInstance(url: String, editorName: String): WebProxyFragment {
            val f = WebProxyFragment()
            f.arguments = Bundle().apply {
                putString(ARG_URL, url)
                putString(ARG_NAME, editorName)
            }
            return f
        }
    }

    private lateinit var webView: WebView
    private var controller: BridgeController? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* result ignored — controller errors back to JS if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            pageUrl = it.getString(ARG_URL) ?: ""
            editorName = it.getString(ARG_NAME)
        }
        Log.d(TAG, "created for editor=$editorName url=$pageUrl")
        requestBlePermissionsIfNeeded()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = View.generateViewId() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = WebView(requireContext())
        val marginBottom = (70 * resources.displayMetrics.density).toInt()
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { bottomMargin = marginBottom }
        (view as ViewGroup).addView(webView, params)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            defaultTextEncodingName = "utf-8"
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
        }

        val ctrl = BridgeController(requireContext().applicationContext, webView, viewLifecycleOwner)
        controller = ctrl
        webView.addJavascriptInterface(CalliopeProxyBridge(ctrl), CalliopeProxyBridge.JS_NAME)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // The widget's detection probes `window.CalliopeNative` on
                // every call to `isNativeMode()`. The injection already
                // happened via `addJavascriptInterface`; nothing else
                // needs to run here.
            }
        }

        Log.d(TAG, "loading $pageUrl")
        webView.loadUrl(pageUrl)
    }

    private fun requestBlePermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!has(Manifest.permission.BLUETOOTH_CONNECT)) perms += Manifest.permission.BLUETOOTH_CONNECT
            if (!has(Manifest.permission.BLUETOOTH_SCAN)) perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) perms += Manifest.permission.ACCESS_FINE_LOCATION
            if (!has(Manifest.permission.BLUETOOTH)) perms += Manifest.permission.BLUETOOTH
            if (!has(Manifest.permission.BLUETOOTH_ADMIN)) perms += Manifest.permission.BLUETOOTH_ADMIN
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun has(p: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        try { controller?.destroy() } catch (_: Throwable) {}
        controller = null
        try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Throwable) {}
        try { webView.destroy() } catch (_: Throwable) {}
    }
}
