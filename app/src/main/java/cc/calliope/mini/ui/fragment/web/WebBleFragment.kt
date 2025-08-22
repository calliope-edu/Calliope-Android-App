package cc.calliope.mini.ui.fragment.web

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cc.calliope.mini.R
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class WebBleFragment : Fragment() {

    // ==== CONFIG ====
    private val pageUrl = "https://cardboard.lofirobot.com/control-calliope/"
    private val deviceMac = "D8:0F:45:61:FE:65"

    // NUS UUIDs
    private val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_RX_UUID: UUID      = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
    private val NUS_TX_UUID: UUID      = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify
    private val CCCD_UUID: UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ==== UI ====
    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBleBridge

    // ==== BLE State ====
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23 // default; payload = mtu-3
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isWriting = AtomicBoolean(false)

    // ==== Permissions ====
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op: we re-check before connect() */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissionsIfNeeded()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Simple container; we’ll create the WebView manually to have full control
        return FrameLayout(requireContext()).apply { id = View.generateViewId() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = WebView(requireContext())
        (view as ViewGroup).addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            defaultTextEncodingName = "utf-8"
            // Optional tweaks
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        bridge = AndroidBleBridge(requireContext())
        webView.addJavascriptInterface(bridge, "AndroidBle")
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                injectBridgeJs()
            }
        }

        webView.loadUrl(pageUrl)
    }

    // === JS injection: rewire page functions to Android bridge ===
    private fun injectBridgeJs() {
        val js = """
            (function(){
              console.log('[Bridge] Injecting Android BLE bridge...');
              // Page sends text lines; we keep the same API name the page expects.
              window.sendUART = function(s){
                try { AndroidBle.writeText(String(s)); } catch(e){ console.error(e); }
              };
              // Connect button callback on the page
              window.connectButtonPressed = function(){
                try { AndroidBle.connect(); } catch(e){ console.error(e); }
              };
              // Optional: the page can handle inbound UART here
              if (!window.onUart) {
                window.onUart = function(s){ console.log('[UART RX]', s); };
              }
              if (!window.onBleReady) {
                window.onBleReady = function(){ console.log('[BLE] Ready'); };
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // === Permissions ===
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

    // === Bridge called from JS ===
    inner class AndroidBleBridge(private val appCtx: Context) {

        @JavascriptInterface
        fun connect() {
            runOnUi {
                if (!ensureBleReady()) return@runOnUi
                if (gatt != null) {
                    logToJs("Already connected or connecting")
                    return@runOnUi
                }
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val dev = try { adapter.getRemoteDevice(deviceMac) } catch (e: Exception) { null }
                if (dev == null) {
                    logToJs("Invalid MAC or BT adapter missing")
                    return@runOnUi
                }

                logToJs("Connecting to $deviceMac ...")
                // TRANSPORT_LE ensures BLE
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dev.connectGatt(appCtx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    dev.connectGatt(appCtx, false, gattCallback)
                }
            }
        }

        @JavascriptInterface
        fun writeText(s: String) {
            val line = if (s.endsWith("\n")) s else "$s\n"
            val bytes = line.toByteArray(StandardCharsets.UTF_8)
            enqueueWrite(bytes)
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUi { closeGatt("disconnect() requested") }
        }
    }

    // === GATT callback ===
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logToJs("GATT connected (status=$status), requesting MTU...")
                    g.requestMtu(247) // typical max on Android; payload ≈ 244
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logToJs("GATT disconnected (status=$status)")
                    rxChar = null; txChar = null
                    negotiatedMtu = 23
                    isWriting.set(false)
                    writeQueue.clear()
                    gatt = null
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            logToJs("MTU = $negotiatedMtu; discovering services...")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logToJs("Service discovery failed: $status")
                return
            }
            val nus = g.getService(NUS_SERVICE_UUID)
            if (nus == null) {
                logToJs("NUS service not found")
                return
            }
            rxChar = nus.getCharacteristic(NUS_RX_UUID)
            txChar = nus.getCharacteristic(NUS_TX_UUID)
            if (rxChar == null || txChar == null) {
                logToJs("NUS RX/TX characteristics missing")
                return
            }

            // Configure write type (NUS usually uses Write Without Response)
            rxChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            // Enable notifications on TX
            val okSet = g.setCharacteristicNotification(txChar, true)
            val cccd = txChar?.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            logToJs("NUS ready (notify=$okSet)")
            evalJs("if(window.onBleReady){onBleReady();}")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == NUS_TX_UUID) {
                val s = String(ch.value ?: ByteArray(0), StandardCharsets.UTF_8)
                evalJs("if(window.onUart){onUart(${jsString(s)});}")
                logD("RX: $s")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            logD("onWrite ${ch.uuid} status=$status")
            isWriting.set(false)
            drainQueue()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            logD("onDescriptorWrite ${d.uuid} status=$status")
        }
    }

    // === Write queue & helpers ===
    private fun enqueueWrite(data: ByteArray) {
        runOnUi {
            if (gatt == null || rxChar == null) {
                logToJs("Not connected")
                return@runOnUi
            }
            val mtuPayload = (negotiatedMtu - 3).coerceAtLeast(20)
            var offset = 0
            while (offset < data.size) {
                val end = (offset + mtuPayload).coerceAtMost(data.size)
                writeQueue.add(data.copyOfRange(offset, end))
                offset = end
            }
            drainQueue()
        }
    }

    private fun drainQueue() {
        if (isWriting.get()) return
        val next = writeQueue.poll() ?: return
        val g = gatt ?: return
        val ch = rxChar ?: return

        isWriting.set(true)
        ch.value = next
        val ok = g.writeCharacteristic(ch)
        if (!ok) {
            logToJs("writeCharacteristic returned false")
            isWriting.set(false)
        }
    }

    private fun ensureBleReady(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            logToJs("Bluetooth disabled")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!has(Manifest.permission.BLUETOOTH_CONNECT) || !has(Manifest.permission.BLUETOOTH_SCAN)) {
                requestBlePermissionsIfNeeded()
                logToJs("Permissions required (BLUETOOTH_CONNECT/SCAN)")
                return false
            }
        } else {
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestBlePermissionsIfNeeded()
                logToJs("Permission required (ACCESS_FINE_LOCATION)")
                return false
            }
        }
        return true
    }

    private fun closeGatt(reason: String) {
        logToJs("Closing GATT: $reason")
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        txChar = null
        negotiatedMtu = 23
        isWriting.set(false)
        writeQueue.clear()
    }

    private fun runOnUi(block: () -> Unit) {
        if (!isAdded) return
        webView.post { block() }
    }

    private fun evalJs(js: String) {
        if (!isAdded) return
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun logToJs(msg: String) {
        Log.d("WebBle", msg)
        evalJs("console.log(${jsString("[Android] $msg")});")
    }

    private fun logD(msg: String) = Log.d("WebBle", msg)

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    override fun onDestroyView() {
        super.onDestroyView()
        closeGatt("onDestroyView")
        try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Throwable) {}
        webView.destroy()
    }
}