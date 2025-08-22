package cc.calliope.mini.ui.fragment.web

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.preference.PreferenceManager
import cc.calliope.mini.utils.Constants
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class WebBleFragment : Fragment() {

    private var pageUrl: String = "https://cardboard.lofirobot.com/control-calliope/"
    private var deviceMac: String = ""
    private var editorUrl: String? = null
    private var editorName: String? = null

    companion object {
        private const val TARGET_URL = "editorUrl"
        private const val TARGET_NAME = "editorName"
        fun newInstance(url: String, editorName: String): WebBleFragment {
            val f = WebBleFragment()
            val args = Bundle()
            args.putString(TARGET_URL, url)
            args.putString(TARGET_NAME, editorName)
            f.arguments = args
            return f
        }
    }

    private val UART_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val UART_TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val UART_RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBleBridge

    private var gatt: BluetoothGatt? = null
    private var txNotifyChar: BluetoothGattCharacteristic? = null // 0002
    private var rxWriteChar: BluetoothGattCharacteristic? = null  // 0003
    private var negotiatedMtu: Int = 23
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isWriting = AtomicBoolean(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            editorUrl = it.getString(TARGET_URL)
            editorName = it.getString(TARGET_NAME)
            if (editorUrl != null) pageUrl = editorUrl!!
        }
        
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        deviceMac = preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""
        
        requestBlePermissionsIfNeeded()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
              }
              function ensure(){
                try{
                  if(String(window.sendUART||'').indexOf('AndroidBle.writeText')===-1){ window.sendUART = function(s){ SEND(s); }; }
                  if(String(window.buttonPressed||'').indexOf('SEND(')===-1){ window.buttonPressed = function(name){ SEND(name); }; }
                }catch(e){}
                setTimeout(ensure,1000);
              }
              hook(); setTimeout(ensure,1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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

    @SuppressLint("MissingPermission")
    inner class AndroidBleBridge(private val appCtx: Context) {
        @JavascriptInterface
        fun connect() {
            runOnUi {
                if (!ensureBleReady()) return@runOnUi
                if (gatt != null) return@runOnUi
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val dev = try { adapter.getRemoteDevice(deviceMac) } catch (_: Exception) { null } ?: return@runOnUi
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
            enqueueWrite(line.toByteArray(StandardCharsets.UTF_8))
        }
        @JavascriptInterface
        fun disconnect() { runOnUi { closeGatt("disconnect") } }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    txNotifyChar = null; rxWriteChar = null
                    negotiatedMtu = 23
                    isWriting.set(false)
                    writeQueue.clear()
                    gatt = null
                }
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(UART_SERVICE_UUID) ?: return
            txNotifyChar = svc.getCharacteristic(UART_TX_CHARACTERISTIC_UUID) // 0002 notify
            rxWriteChar = svc.getCharacteristic(UART_RX_CHARACTERISTIC_UUID)  // 0003 write
            if (txNotifyChar == null || rxWriteChar == null) return
            rxWriteChar?.let { ch ->
                val props = ch.properties
                ch.writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            val okSet = g.setCharacteristicNotification(txNotifyChar, true)
            val cccd = txNotifyChar?.getDescriptor(CCCD_UUID)
            if (okSet && cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            } else {
                evalJs("if(window.onBleReady){onBleReady();}")
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID) evalJs("if(window.onBleReady){onBleReady();}")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == UART_TX_CHARACTERISTIC_UUID) {
                val s = String(ch.value ?: ByteArray(0), StandardCharsets.UTF_8)
                evalJs("if(window.onUart){onUart(${jsString(s)});}")
            }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            isWriting.set(false); drainQueue()
        }
    }

    private fun enqueueWrite(data: ByteArray) {
        runOnUi {
            val g = gatt
            val ch = rxWriteChar
            if (g == null || ch == null) return@runOnUi
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

    @SuppressLint("MissingPermission")
    private fun drainQueue() {
        if (isWriting.get()) return
        val next = writeQueue.poll() ?: return
        val g = gatt ?: return
        val ch = rxWriteChar ?: return
        isWriting.set(true)
        ch.value = next
        val noResp = ch.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = g.writeCharacteristic(ch)
        if (!ok) { isWriting.set(false); return }
        if (noResp) {
            isWriting.set(false)
            webView.postDelayed({ drainQueue() }, 12)
        }
    }

    private fun ensureBleReady(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            has(Manifest.permission.BLUETOOTH_CONNECT) && has(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            has(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(reason: String) {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        txNotifyChar = null
        rxWriteChar = null
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

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    override fun onDestroyView() {
        super.onDestroyView()
        closeGatt("onDestroyView")
        try { (webView.parent as? ViewGroup)?.removeView(webView) } catch (_: Throwable) {}
        webView.destroy()
    }
}