package cc.calliope.mini.ui.fragment.web

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import cc.calliope.mini.R
import cc.calliope.mini.core.bluetooth.UartSession
import cc.calliope.mini.core.state.AppStateRepository
import cc.calliope.mini.core.state.Notification.INFO
import cc.calliope.mini.ui.activity.CameraPermissionActivity
import cc.calliope.mini.ui.model.EditorType
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
import kotlinx.coroutines.launch

/**
 * Hosts the Cardboard editors (control / face) and bridges their UART calls
 * to a native [UartSession] with the current board. The fragment is a thin
 * JS bridge; the GATT work lives in the session.
 *
 * Connection follows the same pattern as the Blocks editor: connect
 * silently in the background as soon as the page is up and the board is in
 * range, reconnect after a drop, and keep the page's own robot button as a
 * manual (re)connect. The page's connected indicator (`robotShow_connected`
 * on `#robotShow`) is mirrored from the native link state, since the page's
 * own Web Bluetooth code — which used to toggle it — never runs here.
 */
class WebBleFragment : Fragment() {

    private var pageUrl: String = "https://cardboard.lofirobot.com/control-calliope/"
    private var deviceMac: String = ""
    private var editorUrl: String? = null
    private var editorName: String? = null

    companion object {
        private const val TAG = "WebBleFragment"
        private const val TARGET_URL = "editorUrl"
        private const val TARGET_NAME = "editorName"
        /** First reconnect delay after a drop; doubles up to [RECONNECT_MAX_MS]. */
        private const val RECONNECT_BASE_MS = 1500L
        private const val RECONNECT_MAX_MS = 10_000L
        /** Consecutive attempts without a ready UART link before auto-connect
         *  gives up (until the board leaves and re-enters range). Firmware that
         *  demands pairing on every connect raises a system dialog per attempt,
         *  so this must stay small. */
        private const val MAX_RECONNECT_ATTEMPTS = 3
        fun newInstance(url: String, editorName: String): WebBleFragment {
            val f = WebBleFragment()
            val args = Bundle()
            args.putString(TARGET_URL, url)
            args.putString(TARGET_NAME, editorName)
            f.arguments = args
            return f
        }
    }

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBleBridge
    private var uart: UartSession? = null

    // ---- Auto-connect driver ----------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    /** The page has loaded once: its hooks exist, so the link may be reported to it. */
    private var pageReady = false
    /** Cleared by an explicit JS disconnect(); set again by connect(). */
    private var autoConnectEnabled = true
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { tryAutoConnect("retry") }

    private var cameraPermissionCallback: ((Boolean) -> Unit)? = null
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        Log.d(TAG, "Camera permission result: $granted")
        cameraPermissionCallback?.invoke(granted)
        cameraPermissionCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            editorUrl = it.getString(TARGET_URL)
            editorName = it.getString(TARGET_NAME)
            if (editorUrl != null) pageUrl = editorUrl!!
        }

        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        deviceMac = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""

        Log.d(TAG, "WebBleFragment created for editor: $editorName, URL: $pageUrl")
        Log.d(TAG, "Bluetooth device MAC: $deviceMac")
        Log.d(TAG, "Camera permission available: ${has(Manifest.permission.CAMERA)}")

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply { id = View.generateViewId() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        uart = UartSession(requireContext(), uartListener)
        webView = WebView(requireContext())
        val marginBottom = resources.getDimensionPixelSize(R.dimen.bottom_bar_clearance)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { bottomMargin = marginBottom }
        (view as ViewGroup).addView(webView, params)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            defaultTextEncodingName = "utf-8"
            useWideViewPort = true
            loadWithOverviewMode = true

            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }
        bridge = AndroidBleBridge()
        webView.addJavascriptInterface(bridge, "AndroidBle")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d(TAG, "onPermissionRequest: ${request.resources.joinToString()}")
                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    // Only allow camera access for CARDBOARD_FACE editor
                    if (editorName != EditorType.CARDBOARD_FACE.directoryName) {
                        Log.d(TAG, "Camera access denied - not CARDBOARD_FACE editor")
                        request.deny()
                        return
                    }

                    if (has(Manifest.permission.CAMERA)) {
                        Log.d(TAG, "Granting camera permission to WebView")
                        request.grant(request.resources)
                    } else {
                        Log.d(TAG, "Camera permission not granted, requesting...")
                        requestCameraPermission { granted ->
                            if (granted) {
                                request.grant(request.resources)
                            } else {
                                request.deny()
                            }
                        }
                    }
                } else {
                    request.grant(request.resources)
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(v: WebView, url: String) {
                super.onPageCommitVisible(v, url)
                // Before first paint, so the button never flashes.
                hideConnectButton()
            }
            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                hideConnectButton()
                injectBridgeJs()
                pageReady = true
                // A reload while linked: restore the indicator the page lost.
                if (uart?.isReady == true) setPageConnected(true)
                tryAutoConnect("page loaded")
            }
        }
        Log.d(TAG, "Loading URL in WebBleFragment: $pageUrl")
        webView.loadUrl(pageUrl)

        // Connect whenever the board comes into range (CheckService keeps
        // scanning while there is no control session). The StateFlow replays
        // its current value, so this also covers "already in range at start".
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppStateRepository.deviceAvailable.collect { available ->
                    if (available) {
                        // Back in range (or a board reset): start a fresh budget.
                        reconnectAttempts = 0
                        tryAutoConnect("device available")
                    } else {
                        mainHandler.removeCallbacks(reconnectRunnable)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacksAndMessages(null)
        autoConnectEnabled = false
        pageReady = false
        uart?.disconnect()
        uart = null
        try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Throwable) {}
        webView.destroy()
        AppStateRepository.setControl(false)
    }

    // ---- UART link -------------------------------------------------------------

    /** Delivered on the Bluetooth binder thread; everything hops to the main thread. */
    private val uartListener = object : UartSession.Listener {
        override fun onConnected() {
            // Report the control session only once the link is real, not
            // optimistically at connect time. The FAB's "Disconnect" ends it
            // like an explicit JS disconnect: the driver stays off until the
            // user leaves and re-enters the editor.
            AppStateRepository.updateNotification(INFO, R.string.flashing_device_connected)
            AppStateRepository.setControl(true) { bridge.disconnect() }
        }

        override fun onReady() {
            runOnUi { setPageConnected(true) }
        }

        override fun onDisconnected(status: Int, hadLink: Boolean) {
            // A dropped link ends the control session — without this the
            // FAB stayed orange and the Connect menu item stayed hidden.
            AppStateRepository.setControl(false)
            Log.d(TAG, "disconnected (status=$status, hadLink=$hadLink)")
            runOnUi {
                if (hadLink) setPageConnected(false)
                scheduleReconnect()
            }
        }

        override fun onText(text: String) {
            evalJs("if(window.onUart){onUart(${jsString(text)});}")
        }
    }

    /** Silent connect if the page is up, the board is in range and nothing is connected. */
    private fun tryAutoConnect(reason: String) {
        mainHandler.removeCallbacks(reconnectRunnable)
        if (!isAdded || view == null || !pageReady || !autoConnectEnabled) return
        if (uart?.isConnected != false || deviceMac.isEmpty()) return
        if (!AppStateRepository.deviceAvailable.value) return
        Log.d(TAG, "auto-connect ($reason)")
        startConnect()
    }

    /** Exponential back-off after a drop or a failed attempt, while the board stays in range. */
    private fun scheduleReconnect() {
        if (!isAdded || view == null || !autoConnectEnabled) return
        if (!AppStateRepository.deviceAvailable.value) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "auto-connect gave up after $reconnectAttempts attempts")
            return
        }
        val delay = (RECONNECT_BASE_MS shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempts++
        Log.d(TAG, "reconnect in ${delay}ms (attempt $reconnectAttempts)")
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun startConnect() {
        if (!isAdded || !ensureBleReady()) return
        val session = uart ?: return
        if (session.isConnected) return
        val adapter = BluetoothUtils.getAdapter(requireContext()) ?: return
        val dev: BluetoothDevice = try { adapter.getRemoteDevice(deviceMac) } catch (_: Exception) { null } ?: return
        Log.d(TAG, "Initiating GATT connection to $deviceMac")
        session.connect(dev)
    }

    /**
     * Hide the page controls the app replaces:
     *  - `#robotShow`, the page's Web Bluetooth "connect" button — the link is
     *    handled natively and connects on its own; the FAB shows the state.
     *  - `#fullscreenBtn` (face app) — the page's Fullscreen API does nothing
     *    inside the WebView; the app has its own full-screen mode in the FAB menu.
     * Both sit in the absolutely positioned top-right menu, so nothing reflows.
     */
    private fun hideConnectButton() {
        evalJs("(function(){if(document.getElementById('__calliopeHideConnect'))return;" +
               "var s=document.createElement('style');s.id='__calliopeHideConnect';" +
               "s.textContent='#robotShow,#fullscreenBtn{display:none !important;}';" +
               "(document.head||document.documentElement).appendChild(s);})();")
    }

    /** Mirror the link state into the page: its (hidden) robot button class and the page hooks. */
    private fun setPageConnected(connected: Boolean) {
        // A ready UART link is the only thing that resets the retry budget;
        // a bare GATT connect that drops again must not.
        if (connected) reconnectAttempts = 0
        val op = if (connected) "add" else "remove"
        val hook = if (connected) "if(window.onBleReady){onBleReady();}"
                   else "if(window.onBleDisconnected){onBleDisconnected();}"
        evalJs("(function(){var b=document.getElementById('robotShow');" +
               "if(b){b.classList.$op('robotShow_connected');}$hook})();")
    }

    private fun injectBridgeJs() {
        val js = """
            (function(){
              function SEND(s){ try{ AndroidBle.writeText(String(s)); }catch(e){} }
              function hook(){
                window.sendUART = function(s){ SEND(s); };
                window.buttonPressed = function(name){ SEND(name); };
                window.connectButtonPressed = function(){ try{ AndroidBle.connect(); }catch(e){} };
                window.onUart = window.onUart || function(s){ console.log('[UART RX]', s); };
                window.onBleReady = window.onBleReady || function(){ console.log('[BLE] Ready'); };

                // Camera functions
                window.isCameraAvailable = function(){
                  try{ return AndroidBle.isCameraAvailable(); }catch(e){ return false; }
                };
                window.requestCameraPermission = function(){
                  try{ return AndroidBle.requestCameraPermission(); }catch(e){ return false; }
                };

                console.log('[Bridge] Camera support injected');
                console.log('[Bridge] Editor type: $editorName');
              }
              function ensure(){
                try{
                  if(String(window.sendUART||'').indexOf('AndroidBle.writeText')===-1){ window.sendUART = function(s){ SEND(s); }; }
                  if(String(window.buttonPressed||'').indexOf('SEND(')===-1){ window.buttonPressed = function(name){ SEND(name); }; }
                  if(String(window.isCameraAvailable||'').indexOf('AndroidBle.isCameraAvailable')===-1){
                    window.isCameraAvailable = function(){ try{ return AndroidBle.isCameraAvailable(); }catch(e){ return false; } };
                  }
                }catch(e){}
                setTimeout(ensure,1000);
              }
              hook(); setTimeout(ensure,1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun has(p: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission(callback: (Boolean) -> Unit) {
        cameraPermissionCallback = callback
        val intent = Intent(requireContext(), CameraPermissionActivity::class.java)
        cameraPermissionLauncher.launch(intent)
    }

    /**
     * Runtime permissions are the activity's business (PermissionGate sends
     * the user to grant them before this screen is usable); here they are
     * only checked, so a connect is never attempted without them.
     */
    private fun ensureBleReady(): Boolean {
        val adapter = BluetoothUtils.getAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) return false
        return Permission.isBleAccessGranted(requireContext())
    }

    // ---- JS bridge -------------------------------------------------------------

    inner class AndroidBleBridge {
        /** Manual (re)connect from the page's robot button; re-arms auto-connect. */
        @JavascriptInterface
        fun connect() {
            runOnUi {
                autoConnectEnabled = true
                reconnectAttempts = 0
                startConnect()
            }
        }

        @JavascriptInterface
        fun writeText(s: String) {
            uart?.send(s)
        }

        /** Explicit disconnect from the page: stays disconnected until connect(). */
        @JavascriptInterface
        fun disconnect() {
            runOnUi {
                autoConnectEnabled = false
                mainHandler.removeCallbacks(reconnectRunnable)
                uart?.disconnect()
            }
        }

        @JavascriptInterface
        fun requestCameraPermission(): Boolean {
            Log.d(TAG, "JavaScript requested camera permission")
            // Only allow camera for CARDBOARD_FACE editor
            return editorName == EditorType.CARDBOARD_FACE.directoryName && has(Manifest.permission.CAMERA)
        }

        @JavascriptInterface
        fun isCameraAvailable(): Boolean {
            // Only allow camera for CARDBOARD_FACE editor
            val available = editorName == EditorType.CARDBOARD_FACE.directoryName && has(Manifest.permission.CAMERA)
            Log.d(TAG, "Camera availability check: $available (editor: $editorName)")
            return available
        }
    }

    private fun runOnUi(block: () -> Unit) {
        if (!isAdded) return
        mainHandler.post { if (isAdded && view != null) block() }
    }

    private fun evalJs(js: String) {
        if (!isAdded) return
        mainHandler.post { if (isAdded && view != null) webView.evaluateJavascript(js, null) }
    }

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
