package cc.calliope.mini.bridge

import android.annotation.SuppressLint
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
import cc.calliope.mini.R
import cc.calliope.mini.core.service.FlashingService
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.Event
import cc.calliope.mini.core.state.Notification
import cc.calliope.mini.core.state.Progress
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.Constants
import cc.calliope.mini.utils.bluetooth.BluetoothUtils
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

    /**
     * Whether we've told the app it's in a live-control session. Drives the
     * native movable FAB colour via [ApplicationStateHandler], exactly like
     * the cardboard editor and the Scratch Link bridge: STATE_CONTROL while
     * the campus session holds a GATT connection, STATE_IDLE once it drops.
     * Main-thread only.
     */
    private var controlReported = false

    private fun reportControl() {
        if (!controlReported) {
            controlReported = true
            ApplicationStateHandler.updateState(State.STATE_CONTROL)
        }
    }

    private fun reportIdle() {
        if (controlReported) {
            controlReported = false
            ApplicationStateHandler.updateState(State.STATE_IDLE)
        }
    }

    // ---- Lifecycle ---------------------------------------------------------

    /** Set once the host fragment tears the view down. After this, [post]
     *  refuses to touch the WebView — BLE callbacks can still arrive on a
     *  binder thread after onDestroyView() and would otherwise call
     *  evaluateJavascript() on a destroyed WebView. */
    @Volatile
    private var destroyed = false

    fun destroy() {
        // Mark destroyed BEFORE disconnecting: session.disconnect() can
        // trigger an async onDisconnected -> emitState -> post() that would
        // otherwise race the WebView teardown.
        destroyed = true
        // Runs on the main thread (fragment onDestroyView). Idempotent with
        // the reportIdle() the disconnect callback will also fire.
        reportIdle()
        // LifecycleOwner removes the observers automatically when its state
        // hits DESTROYED — no manual cleanup needed for them.
        session.disconnect()
    }

    // ---- JS dispatch -------------------------------------------------------

    /** Top-level URL the WebView is currently showing, updated by the host
     *  WebViewClient. Gates [dispatch] so the bridge only ever services a
     *  real campus origin — the firmware-flashing capability must not leak
     *  to a page the campus site navigates or links out to. */
    @Volatile
    private var currentPageUrl: String? = null

    fun onPageUrl(url: String?) {
        currentPageUrl = url
    }

    fun dispatch(id: String, op: String, args: JSONObject) {
        // @JavascriptInterface delivers on a WebView binder thread. All
        // controller state (pending reply ids, flash flags, notifySubs) is
        // confined to the main thread — where the LiveData observers also
        // run — so hop over before touching anything.
        main.post { dispatchOnMain(id, op, args) }
    }

    private fun dispatchOnMain(id: String, op: String, args: JSONObject) {
        if (!CampusUrls.isCampusUrl(currentPageUrl)) {
            Log.w(TAG, "dispatch refused — origin not in campus allowlist: $currentPageUrl")
            replyError(id, "bridge unavailable for this origin")
            return
        }
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
    private var connectTimeout: Runnable? = null

    /** Overall connect ceiling. The BLE stack itself fails at ~30 s for an
     *  unreachable device, but a device that connects yet never completes
     *  service discovery would otherwise leave the JS promise pending
     *  forever — this guarantees a reply either way. Main-thread only. */
    private fun scheduleConnectTimeout(id: String) {
        cancelConnectTimeout()
        val r = Runnable {
            if (pendingConnectReplyId == id) {
                pendingConnectReplyId = null
                connectTimeout = null
                session.disconnect()
                replyError(id, "connect timed out")
                emitState("ble", status = "error", errorMessage = context.getString(R.string.bridge_connect_timeout))
            }
        }
        connectTimeout = r
        main.postDelayed(r, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        connectTimeout?.let { main.removeCallbacks(it) }
        connectTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun handleConnect(id: String, args: JSONObject) {
        val transport = args.optString("transport", "ble")
        if (transport != "ble") {
            replyError(id, "transport=$transport not supported in proxy mode (BLE only)")
            return
        }
        // Reject overlapping connects: a second connect would overwrite
        // pendingConnectReplyId and orphan the first JS promise.
        if (pendingConnectReplyId != null) {
            replyError(id, "connect already in progress")
            return
        }
        emitState("ble", status = "connecting", errorMessage = "")
        val adapter = BluetoothUtils.getAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            val msg = context.getString(R.string.error_bluetooth_not_enabled)
            replyError(id, msg)
            emitState("ble", status = "error", errorMessage = msg)
            return
        }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val mac = prefs.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""
        if (mac.isEmpty()) {
            val msg = context.getString(R.string.bridge_no_paired_device)
            replyError(id, msg)
            emitState("ble", status = "error", errorMessage = msg)
            return
        }
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) {
            val msg = context.getString(R.string.bridge_invalid_device_address)
            replyError(id, msg)
            emitState("ble", status = "error", errorMessage = msg)
            return
        }
        // Reply only after services are discovered (onConnected callback)
        pendingConnectReplyId = id
        scheduleConnectTimeout(id)
        session.connect(device)
    }

    private fun handleDisconnect(id: String, args: JSONObject) {
        notifySubs.clear()
        session.disconnect()
        emitState("ble", status = "disconnected", deviceName = "")
        replyOk(id)
    }

    // BridgeBleSession delivers these on a binder thread; marshal to main so
    // all controller state stays single-threaded.
    override fun onConnected(deviceName: String?) {
        main.post { onConnectedMain(deviceName) }
    }

    override fun onDisconnected(reason: String) {
        main.post { onDisconnectedMain(reason) }
    }

    override fun onError(message: String) {
        main.post { onErrorMain(message) }
    }

    override fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
        main.post { onNotifyMain(serviceUuid, characteristicUuid, data) }
    }

    private fun onConnectedMain(deviceName: String?) {
        val (boardVersion, calliopeVersion) = versionStrings()
        emitState(
            "ble",
            status = "connected",
            deviceName = deviceName ?: "",
            friendlyName = friendlyNameOf(deviceName),
            boardVersion = boardVersion,
            calliopeVersion = calliopeVersion,
            bleCanFlash = true,
            bleCanCommunicate = true,
            bleHasPermission = true,
        )
        reportControl()
        // Auto-subscribe Nordic UART TX so REPL/console/live-data reaches the
        // widget as `serialData` without the web side requesting it. Best-effort:
        // fails silently on programs with no UART service (e.g. MicroPython).
        session.enableNotify(uartService, uartTx) { /* best-effort */ }
        cancelConnectTimeout()
        val id = pendingConnectReplyId
        pendingConnectReplyId = null
        if (id != null) replyOk(id)
    }

    private fun onDisconnectedMain(reason: String) {
        reportIdle()
        notifySubs.clear()
        cancelConnectTimeout()
        emitState("ble", status = "disconnected", deviceName = "")
        val id = pendingConnectReplyId
        pendingConnectReplyId = null
        if (id != null) replyError(id, "connect failed: $reason")
    }

    private fun onErrorMain(message: String) {
        sendEvent("error", JSONObject().put("message", message))
        cancelConnectTimeout()
        val id = pendingConnectReplyId
        pendingConnectReplyId = null
        if (id != null) {
            replyError(id, message)
            emitState("ble", status = "error", errorMessage = message)
        }
    }

    private fun onNotifyMain(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
        // Nordic UART TX → forward as `serialData`, which the widget's serial
        // layer (serial.ts onSerialData/onSerialLine) consumes directly. This
        // is the inbound half of the proxy serial channel; without it proxy
        // serial is write-only.
        if (serviceUuid == uartService && characteristicUuid == uartTx) {
            sendEvent("serialData", JSONObject().put("data", base64Encode(data)))
            return
        }
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
        // Reject overlapping flashes: a second flash would overwrite
        // pendingFlashReplyId and orphan the first JS promise (and
        // FlashingService refuses a concurrent run anyway).
        if (flashInFlight) {
            replyError(id, "flash already in progress")
            return
        }
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

        val argForceFullDfu = args.optBoolean("forceFullDfu", false)

        // Detect blocks-runtime on the device before starting the flash.
        //
        // Partial flash compares only the CODAL DAL hash (region 1) — but
        // both blocks-runtime (pxt-scratch derivative) and pxt-calliope
        // typically share the same underlying CODAL DAL, so the DAL hash
        // check matches when it shouldn't. The PXT runtime difference
        // lives in the gap between the DAL region (~0x3D77C) and the
        // MakeCode region (~0x46000), which partial flash never touches.
        // Result: blocks→makecode swap writes only the user-program area,
        // leaves the blocks runtime in place, and the device ends up in a
        // broken hybrid state.
        //
        // The reliable detection: read the MbitMore STATE characteristic
        // (`0b500101-…`). pxt-blocks-runtime fills it with sensor data
        // (non-zero); MakeCode/MicroPython/the CODAL stub leave it all
        // zeros. If the device returns non-zero, force full DFU
        // regardless of what the hex carries — the partial-flash
        // optimisation can't safely span a runtime swap.
        probeBlocksRuntime { isBlocksRuntime ->
            val forceFullDfu = argForceFullDfu || isBlocksRuntime
            if (isBlocksRuntime && !argForceFullDfu) {
                sendEvent("log", JSONObject()
                    .put("direction", "info")
                    .put("text", context.getString(R.string.bridge_blocks_runtime_detected)))
            }
            beginFlash(id, out, forceFullDfu)
        }
    }

    /**
     * Continues handleFlash after the optional blocks-runtime probe has
     * returned. Split from handleFlash so the probe's async callback can
     * inject `forceFullDfu = true` for the cross-runtime case before we
     * disconnect and kick FlashingService.
     */
    private fun beginFlash(id: String, hexFile: File, forceFullDfu: Boolean) {
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
                intent.putExtra(Constants.EXTRA_FILE_PATH, hexFile.absolutePath)
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

    /** MbitMore service exposed by pxt-blocks-runtime. */
    private val mbitMoreService: UUID = UUID.fromString("0b50f3e4-607f-4151-9091-7d008d6ffc5c")
    private val mbitMoreState: UUID = UUID.fromString("0b500101-607f-4151-9091-7d008d6ffc5c")

    /**
     * Read MbitMore STATE and call `onResult(true)` if the device is
     * running the real pxt-blocks-runtime (STATE filled with sensor data),
     * `onResult(false)` otherwise (zeros, characteristic missing, read
     * failed, not connected, or timed out). The newer CODAL stub registers
     * the service unconditionally so service-presence alone is not enough;
     * STATE content is the discriminator (mirrors the widget's
     * program-type.ts `probeBle` heuristic).
     */
    private fun probeBlocksRuntime(onResult: (Boolean) -> Unit) {
        if (!session.isConnected) {
            onResult(false)
            return
        }
        // `settled` is touched only on the main thread; the session read
        // callback fires on a binder thread, so it hops to main before
        // settling. This guarantees onResult (which leads into beginFlash)
        // runs on the main thread too.
        var settled = false
        lateinit var timeout: Runnable
        val finish: (Boolean) -> Unit = { result ->
            if (!settled) {
                settled = true
                main.removeCallbacks(timeout)
                onResult(result)
            }
        }
        timeout = Runnable { finish(false) }
        main.postDelayed(timeout, 1500)
        session.read(mbitMoreService, mbitMoreState) { data ->
            val anyNonZero = data != null && data.any { it != 0.toByte() }
            main.post { finish(anyNonZero) }
        }
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
        boardVersion: String? = null,
        calliopeVersion: String? = null,
        bleCanFlash: Boolean? = null,
        bleCanCommunicate: Boolean? = null,
        bleHasPermission: Boolean? = null,
    ) {
        val d = JSONObject().put("transport", transport)
        if (status != null) d.put("status", status)
        if (deviceName != null) d.put("deviceName", deviceName)
        if (errorMessage != null) d.put("errorMessage", errorMessage)
        if (friendlyName != null) d.put("friendlyName", friendlyName)
        if (boardVersion != null) d.put("boardVersion", boardVersion)
        if (calliopeVersion != null) d.put("calliopeVersion", calliopeVersion)
        if (bleCanFlash != null) d.put("bleCanFlash", bleCanFlash)
        if (bleCanCommunicate != null) d.put("bleCanCommunicate", bleCanCommunicate)
        if (bleHasPermission != null) d.put("bleHasPermission", bleHasPermission)
        sendEvent("state", d)
    }

    /**
     * Map the persisted chip class (BondingService writes
     * [Constants.CURRENT_DEVICE_VERSION]) to the widget's version strings, so
     * the web layer doesn't have to guess. Returns (boardVersion, calliopeVersion).
     *
     * Campus semantics: boardVersion is the silicon class ("V1" = nRF51,
     * "V2" = nRF52), calliopeVersion is the product generation ("V1" = mini 1,
     * "V2" = mini 2, "V3" = mini 3). BLE can't tell a mini 1 from a mini 2
     * (both nRF51), so the nRF51 class is reported as mini 2 — agreed with the
     * campus team: mini 1 hardware is practically extinct, reporting "V1" made
     * the campus Blocks banner reject every real mini 2, and campus keeps its
     * own RAM-fit gate for a genuine mini 1. Unidentified → (null, null).
     */
    private fun versionStrings(): Pair<String?, String?> {
        val v = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(Constants.CURRENT_DEVICE_VERSION, Constants.UNIDENTIFIED)
        return when (v) {
            Constants.MINI_V3 -> "V2" to "V3"
            Constants.MINI_V2 -> "V1" to "V2"
            else -> null to null
        }
    }

    /** Pull the 5-letter CVCVC friendly name out of an advertised name like
     *  "Calliope mini [zuvav]". Null if absent. */
    private fun friendlyNameOf(deviceName: String?): String? {
        if (deviceName == null) return null
        return Regex("[zvgpt][uoiea][zvgpt][uoiea][zvgpt]", RegexOption.IGNORE_CASE)
            .find(deviceName)?.value?.lowercase()
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
        if (destroyed) return
        val payload = msg.toString()
        // The Native-Web envelope is wrapped in JSON.parse on the web side
        // when it's a string; passing the JSON string itself is the safest
        // way to round-trip across the JS-bridge boundary.
        val escaped = JSONArray().put(payload).toString()
            .let { it.substring(1, it.length - 1) } // strip array brackets → quoted string
        val js = "if(window.__calliopeNative)window.__calliopeNative.onMessage(${escaped});"
        // Re-check on the main thread: destroy() may have run between the
        // check above (possibly on a binder thread) and this runnable.
        main.post { if (!destroyed) webView.evaluateJavascript(js, null) }
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

    companion object {
        private const val TAG = "BridgeController"
        private const val CONNECT_TIMEOUT_MS = 25_000L
    }
}
