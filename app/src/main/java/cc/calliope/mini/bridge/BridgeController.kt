package cc.calliope.mini.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import cc.calliope.mini.core.service.FlashingService
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Event
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.core.state.Progress
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.Constants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Dispatcher for the Calliope native-proxy bridge.
 *
 * One controller per host fragment. Owns:
 *   - the BLE session ([BridgeBleSession])
 *   - the JS-event sender (writes to `window.__calliopeNative.onMessage`)
 *   - the flash pipeline (hex → temp file → [FlashingService] + progress
 *     receiver → flashProgress events)
 *
 * All env JSON envelopes from the JS side enter here via [dispatch]. The
 * controller MUST be stopped via [destroy] when the host fragment goes
 * away — it holds a WebView reference and a registered receiver.
 */
class BridgeController(
    private val context: Context,
    private val webView: WebView,
    private val lifecycleOwner: LifecycleOwner,
) : BridgeBleSession.Listener {

    private val main = Handler(Looper.getMainLooper())
    private val session = BridgeBleSession(context.applicationContext, this)


    /** GATT characteristics the JS side has subscribed to. We forward
     *  `onCharacteristicChanged` to JS only when this set contains the
     *  exact (service, char) pair — avoids leaking unrelated notifies. */
    private val notifySubs = ConcurrentHashMap.newKeySet<String>()

    // ---- Lifecycle ---------------------------------------------------------

    fun destroy() {
        // LifecycleOwner removes the observers automatically when its state
        // hits DESTROYED — no manual cleanup needed for them.
        session.disconnect()
    }

    // ---- JS dispatch -------------------------------------------------------

    fun dispatch(id: String, op: String, args: JSONObject) {
        try {
            when (op) {
                "connect" -> handleConnect(id, args)
                "disconnect" -> handleDisconnect(id, args)
                "flash" -> handleFlash(id, args)
                "gattRead" -> handleGattRead(id, args)
                "gattWrite" -> handleGattWrite(id, args)
                "gattSubscribe" -> handleGattSubscribe(id, args)
                "gattUnsubscribe" -> handleGattUnsubscribe(id, args)
                "serialWrite" -> handleSerialWrite(id, args)
                else -> replyError(id, "unknown op: $op")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dispatch($op) threw: ${e.message}", e)
            replyError(id, "$op failed: ${e.message ?: e::class.java.simpleName}")
        }
    }

    // ---- Connect / disconnect ---------------------------------------------

    private var pendingConnectReplyId: String? = null

    @SuppressLint("MissingPermission")
    private fun handleConnect(id: String, args: JSONObject) {
        val transport = args.optString("transport", "ble")
        if (transport != "ble") {
            replyError(id, "transport=$transport not supported in proxy mode (BLE only)")
            return
        }
        emitState("ble", status = "connecting", errorMessage = "")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            replyError(id, "Bluetooth is not enabled")
            emitState("ble", status = "error", errorMessage = "Bluetooth disabled")
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val mac = prefs.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""
        if (mac.isEmpty()) {
            replyError(id, "No paired Calliope mini — pair one in the app first")
            emitState("ble", status = "error", errorMessage = "Kein Calliope mini gekoppelt")
            return
        }
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) {
            replyError(id, "Invalid device address: $mac")
            emitState("ble", status = "error", errorMessage = "Ungültige Geräteadresse")
            return
        }
        // Reply only after services are discovered (onConnected callback)
        pendingConnectReplyId = id
        session.connect(device)
    }

    private fun handleDisconnect(id: String, args: JSONObject) {
        notifySubs.clear()
        session.disconnect()
        emitState("ble", status = "disconnected", deviceName = "")
        replyOk(id)
    }

    override fun onConnected(deviceName: String?) {
        emitState(
            "ble",
            status = "connected",
            deviceName = deviceName ?: "",
            bleCanFlash = true,
            bleCanCommunicate = true,
            bleHasPermission = true,
        )
        val id = pendingConnectReplyId
        pendingConnectReplyId = null
        if (id != null) replyOk(id)
    }

    override fun onDisconnected(reason: String) {
        notifySubs.clear()
        emitState("ble", status = "disconnected", deviceName = "")
        val id = pendingConnectReplyId
        pendingConnectReplyId = null
        if (id != null) replyError(id, "connect failed: $reason")
    }

    override fun onError(message: String) {
        sendEvent("error", JSONObject().put("message", message))
        val id = pendingConnectReplyId
        pendingConnectReplyId = null
        if (id != null) {
            replyError(id, message)
            emitState("ble", status = "error", errorMessage = message)
        }
    }

    override fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
        val key = subKey(serviceUuid.toString(), characteristicUuid.toString())
        if (key !in notifySubs) return
        sendEvent(
            "gattNotify",
            JSONObject()
                .put("serviceId", serviceUuid.toString())
                .put("characteristicId", characteristicUuid.toString())
                .put("data", base64Encode(data)),
        )
    }

    // ---- GATT --------------------------------------------------------------

    private fun handleGattRead(id: String, args: JSONObject) {
        val svc = parseUuid(args.opt("serviceId"))
        val ch = parseUuid(args.opt("characteristicId"))
        if (svc == null || ch == null) { replyError(id, "invalid uuid"); return }
        if (!session.isConnected) { replyError(id, "not connected"); return }
        session.read(svc, ch) { data ->
            val payload = JSONObject().put("data", if (data != null) base64Encode(data) else "")
            replyOk(id, payload)
        }
    }

    private fun handleGattWrite(id: String, args: JSONObject) {
        val svc = parseUuid(args.opt("serviceId"))
        val ch = parseUuid(args.opt("characteristicId"))
        if (svc == null || ch == null) { replyError(id, "invalid uuid"); return }
        if (!session.isConnected) { replyError(id, "not connected"); return }
        val data = base64Decode(args.optString("data", ""))
        val withResponse = args.optBoolean("withResponse", false)
        session.write(svc, ch, data, withResponse) { ok ->
            if (ok) replyOk(id) else replyError(id, "write failed")
        }
    }

    private fun handleGattSubscribe(id: String, args: JSONObject) {
        val svc = parseUuid(args.opt("serviceId"))
        val ch = parseUuid(args.opt("characteristicId"))
        if (svc == null || ch == null) { replyError(id, "invalid uuid"); return }
        if (!session.isConnected) { replyError(id, "not connected"); return }
        val key = subKey(svc.toString(), ch.toString())
        notifySubs.add(key)
        session.enableNotify(svc, ch) { ok ->
            if (ok) replyOk(id) else { notifySubs.remove(key); replyError(id, "subscribe failed") }
        }
    }

    private fun handleGattUnsubscribe(id: String, args: JSONObject) {
        val svc = parseUuid(args.opt("serviceId"))
        val ch = parseUuid(args.opt("characteristicId"))
        if (svc == null || ch == null) { replyError(id, "invalid uuid"); return }
        notifySubs.remove(subKey(svc.toString(), ch.toString()))
        if (!session.isConnected) { replyOk(id); return }
        session.disableNotify(svc, ch) { _ -> replyOk(id) }
    }

    // ---- Serial (UART) ----------------------------------------------------

    /** Nordic UART RX (write-from-host) characteristic in CODAL. */
    private val uartService: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val uartRx: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val uartTx: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    private fun handleSerialWrite(id: String, args: JSONObject) {
        if (!session.isConnected) { replyError(id, "not connected"); return }
        val data = base64Decode(args.optString("data", ""))
        session.write(uartService, uartRx, data, withResponse = false) { ok ->
            if (ok) replyOk(id) else replyError(id, "serial write failed")
        }
    }

    // ---- Flash -------------------------------------------------------------

    private var pendingFlashReplyId: String? = null
    /** True from the moment we kick `FlashingService` until either a
     *  `STATE_IDLE` follow-up to `STATE_FLASHING` or a `STATE_ERROR`
     *  arrives. Acts as the filter for the global `ApplicationStateHandler`
     *  observers so we don't react to flashes that started outside the
     *  proxy (legacy editors). */
    private var flashInFlight: Boolean = false
    private var lastStateType: Int = State.STATE_IDLE
    /** Tracks whether the current flash is going down the partial-flash
     *  path or the full Nordic DFU path. Starts as "partial" (matches the
     *  FlashingService default) and flips to "dfu" when a Nordic-DFU-only
     *  progress code arrives — those don't fire during partial flash.
     *  Forwarded to the widget as `partial: true/false` so the UI shows
     *  the correct "Schnelles Flashen" vs "Vollständiges Flashen" label. */
    private var currentFlashMode: String = "partial"

    private val progressObserver = Observer<Progress?> { p ->
        if (!flashInFlight || p == null) return@Observer
        when (val pct = p.value) {
            Progress.PROGRESS_CONNECTING,
            Progress.PROGRESS_STARTING -> {
                // These codes only fire from the Nordic DFU library. Their
                // arrival means PartialFlashingService either declined the
                // hex (RESULT_ATTEMPT_DFU) or wasn't started at all
                // (forceFullDfu) — either way we're now in full DFU.
                currentFlashMode = "dfu"
                emitFlashProgress("prepare", 0)
            }
            Progress.PROGRESS_ENABLING_DFU_MODE -> {
                currentFlashMode = "dfu"
                emitFlashProgress("reboot", 0)
            }
            Progress.PROGRESS_VALIDATING -> emitFlashProgress("finalising", 100)
            Progress.PROGRESS_DISCONNECTING -> { /* swallowed; finalised by state-idle */ }
            Progress.PROGRESS_COMPLETED -> finishFlash(success = true, error = null)
            Progress.PROGRESS_ABORTED -> finishFlash(success = false, error = "flash aborted")
            else -> if (pct in 0..100) emitFlashProgress("flashing", pct)
        }
    }

    private val stateObserver = Observer<State?> { s ->
        if (s == null) return@Observer
        val type = s.type
        if (flashInFlight) {
            when (type) {
                // STATE_ERROR can come from preflight (loadDeviceInfo /
                // checkCompatibility — e.g. V2 hex on V3 board) before any
                // progress fires, OR from a failed DFU step. Surface the
                // most recent ERROR notification as the reason.
                State.STATE_ERROR -> finishFlash(success = false, error = latestErrorMessage ?: "flash failed")
                // STATE_FLASHING → STATE_IDLE is the secondary "done" edge
                // for paths that don't post a PROGRESS_COMPLETED.
                State.STATE_IDLE -> if (lastStateType == State.STATE_FLASHING) {
                    finishFlash(success = true, error = null)
                }
                else -> { /* STATE_BUSY / STATE_CONTROL — uninteresting */ }
            }
        }
        lastStateType = type
    }

    /** Last ERROR-level notification text seen. `FlashingService.handleError`
     *  fires `updateNotification(ERROR, …) + updateState(STATE_ERROR)` but
     *  never `updateError(…)`, so the user-readable reason only reaches us
     *  through this channel. */
    private var latestErrorMessage: String? = null

    private val notificationObserver = Observer<Event<Notification>?> { ev ->
        val n = ev?.peekContent() ?: return@Observer
        when (n.type) {
            Notification.ERROR -> {
                latestErrorMessage = n.message
                if (flashInFlight) {
                    sendEvent("log", JSONObject().put("direction", "error").put("text", n.message ?: ""))
                }
            }
            Notification.INFO, Notification.WARNING -> {
                // Forward only while a flash is in flight so the user sees
                // PartialFlashingService / DfuService progress text in the
                // widget's comms panel — e.g. "Hash mismatch", "Partial
                // flashing failed, falling back to DFU", etc.
                if (flashInFlight && !n.message.isNullOrEmpty()) {
                    sendEvent("log", JSONObject().put("direction", "info").put("text", n.message))
                }
            }
        }
    }

    private val errorObserver = Observer<cc.calliope.mini.core.state.Error?> { e ->
        if (!flashInFlight || e == null) return@Observer
        finishFlash(success = false, error = e.message ?: "flash error ${e.code}")
    }

    init {
        // Observe the existing flash pipeline's progress/state once. Filtering
        // by `flashInFlight` ensures we ignore flashes initiated outside the
        // proxy (e.g. legacy WebFragment editors that share the same service).
        ApplicationStateHandler.getProgressLiveData().observe(lifecycleOwner, progressObserver)
        ApplicationStateHandler.getStateLiveData().observe(lifecycleOwner, stateObserver)
        ApplicationStateHandler.getErrorLiveData().observe(lifecycleOwner, errorObserver)
        ApplicationStateHandler.getNotificationLiveData().observe(lifecycleOwner, notificationObserver)
    }

    private fun finishFlash(success: Boolean, error: String?) {
        if (!flashInFlight) return
        flashInFlight = false
        if (success) {
            emitFlashProgress("finalising", 100)
            sendEvent("flashDone", JSONObject())
        }
        val id = pendingFlashReplyId
        pendingFlashReplyId = null
        if (id != null) {
            if (success) replyOk(id) else replyError(id, error ?: "flash failed")
        }
    }

    private fun handleFlash(id: String, args: JSONObject) {
        val hex = args.optString("hex", "")
        val name = args.optString("name", "project")
        if (hex.isEmpty()) { replyError(id, "flash: empty hex"); return }

        // Persist the hex to a temp file FlashingService can read.
        val outDir = File(context.cacheDir, "bridge-flash").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        val out = File(outDir, "$safeName.hex")
        try {
            // Intel HEX is plain ASCII (0-9, A-F, ':', \r, \n). UTF-8 keeps
            // those bytes 1:1 like US_ASCII would, but doesn't silently
            // replace any non-ASCII char (e.g. a stray BOM) with '?'. The
            // partial-flash service searches for the PXT_MAGIC marker as a
            // text substring, so any silent byte substitution would skip
            // the partial path and fall through to full DFU.
            FileOutputStream(out).use { it.write(hex.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            replyError(id, "could not write hex: ${e.message}")
            return
        }

        // FlashingService reads its target MAC/version from SharedPreferences
        // (already populated by the device-pairing flow). The radio is
        // single-consumer on Android — partial flash needs an exclusive
        // GATT connection. We drop our proxy session and WAIT for the
        // STATE_DISCONNECTED callback (or a 1.5 s timeout) before starting
        // the flash service. Without this, partial flash silently failed
        // because the BT stack was still tearing down our GATT when
        // PartialFlashingService tried to open its own.
        notifySubs.clear()
        emitState("ble", status = "disconnected", deviceName = "")

        val forceFullDfu = args.optBoolean("forceFullDfu", false)
        pendingFlashReplyId = id
        flashInFlight = true
        latestErrorMessage = null
        lastStateType = State.STATE_IDLE
        // Optimistic default: partial. FlashingService runs partial first
        // unless EXTRA_FORCE_FULL_DFU is set. The progress observer flips
        // to "dfu" the moment a DFU-only progress code arrives — that
        // covers both forceFullDfu=true and partial→full fallback.
        currentFlashMode = if (forceFullDfu) "dfu" else "partial"
        emitFlashProgress(phase = "prepare", progress = 0)

        session.disconnect(onClosed = {
            try {
                val intent = Intent(context, FlashingService::class.java)
                intent.putExtra(Constants.EXTRA_FILE_PATH, out.absolutePath)
                if (forceFullDfu) {
                    intent.putExtra(FlashingService.EXTRA_FORCE_FULL_DFU, true)
                }
                context.startService(intent)
            } catch (e: Exception) {
                flashInFlight = false
                pendingFlashReplyId = null
                replyError(id, "could not start flashing service: ${e.message}")
            }
        }, timeoutMs = 1500)
    }

    // ---- Reply / event helpers --------------------------------------------

    private fun replyOk(id: String, data: JSONObject? = null) {
        val msg = JSONObject()
            .put("id", id)
            .put("type", "reply")
        if (data != null) msg.put("data", data)
        post(msg)
    }

    private fun replyError(id: String, message: String) {
        val msg = JSONObject()
            .put("id", id)
            .put("type", "reply")
            .put("error", message)
        post(msg)
    }

    private fun sendEvent(kind: String, data: JSONObject) {
        val msg = JSONObject()
            .put("type", "event")
            .put("kind", kind)
            .put("data", data)
        post(msg)
    }

    private fun emitState(
        transport: String,
        status: String? = null,
        deviceName: String? = null,
        errorMessage: String? = null,
        friendlyName: String? = null,
        bleCanFlash: Boolean? = null,
        bleCanCommunicate: Boolean? = null,
        bleHasPermission: Boolean? = null,
    ) {
        val d = JSONObject().put("transport", transport)
        if (status != null) d.put("status", status)
        if (deviceName != null) d.put("deviceName", deviceName)
        if (errorMessage != null) d.put("errorMessage", errorMessage)
        if (friendlyName != null) d.put("friendlyName", friendlyName)
        if (bleCanFlash != null) d.put("bleCanFlash", bleCanFlash)
        if (bleCanCommunicate != null) d.put("bleCanCommunicate", bleCanCommunicate)
        if (bleHasPermission != null) d.put("bleHasPermission", bleHasPermission)
        sendEvent("state", d)
    }

    private fun emitFlashProgress(phase: String, progress: Int) {
        sendEvent(
            "flashProgress",
            JSONObject()
                .put("transport", "ble")
                .put("phase", phase)
                .put("progress", progress)
                .put("partial", currentFlashMode == "partial"),
        )
    }

    private fun post(msg: JSONObject) {
        val payload = msg.toString()
        // The Native-Web envelope is wrapped in JSON.parse on the web side
        // when it's a string; passing the JSON string itself is the safest
        // way to round-trip across the JS-bridge boundary.
        val escaped = JSONArray().put(payload).toString()
            .let { it.substring(1, it.length - 1) } // strip array brackets → quoted string
        val js = "if(window.__calliopeNative)window.__calliopeNative.onMessage(${escaped});"
        main.post { webView.evaluateJavascript(js, null) }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun parseUuid(any: Any?): UUID? {
        if (any == null) return null
        return try {
            when (any) {
                is Number -> {
                    // 16-bit short UUID → expand using the Bluetooth base.
                    val hex = String.format("%08x", any.toInt() and 0xFFFF)
                    UUID.fromString("$hex-0000-1000-8000-00805f9b34fb")
                }
                else -> UUID.fromString(any.toString().lowercase())
            }
        } catch (_: Exception) { null }
    }

    private fun subKey(svc: String, ch: String) = "${svc.lowercase()}|${ch.lowercase()}"

    private fun base64Encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun base64Decode(s: String): ByteArray =
        if (s.isEmpty()) ByteArray(0) else Base64.decode(s, Base64.NO_WRAP)

    companion object { private const val TAG = "BridgeController" }
}
