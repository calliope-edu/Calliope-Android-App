package cc.calliope.mini.scratchlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import cc.calliope.mini.bridge.BridgeBleSession
import cc.calliope.mini.core.state.ApplicationStateHandler
import cc.calliope.mini.core.state.State
import cc.calliope.mini.utils.Permission
import cc.calliope.mini.utils.Utils
import org.java_websocket.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One `/scratch/ble` JSON-RPC session: one WebSocket == one BLE peripheral.
 *
 * Lifecycle mirrors Scratch Link: the page sends `discover` with Web
 * Bluetooth-style filters, we stream `didDiscoverPeripheral` notifications
 * (the editor draws its own device picker from them), the page calls
 * `connect {peripheralId}`, then GATT traffic flows via `read`/`write`/
 * `startNotifications`. A GATT drop closes the socket — that is how the
 * scratch-vm client learns the peripheral is gone.
 *
 * All state lives on the main thread; WebSocket callbacks are re-posted.
 */
class ScratchLinkBleSession(
    private val appCtx: Context,
    private val socket: WebSocket,
) {

    // ---- Web Bluetooth-style discovery filters -----------------------------

    private class MfgFilter(val id: Int, val dataPrefix: ByteArray?, val mask: ByteArray?)

    private class Filter(
        val name: String?,
        val namePrefix: String?,
        val services: List<UUID>,
        val manufacturer: List<MfgFilter>,
    ) {
        val isTrivial: Boolean
            get() = name == null && namePrefix == null &&
                    services.isEmpty() && manufacturer.isEmpty()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var filters: List<Filter> = emptyList()
    private val allowedServices = LinkedHashSet<UUID>()
    private val discovered = HashMap<String, BluetoothDevice>()
    private val lastAdvertised = HashMap<String, Long>()
    private var scanning = false
    private var gatt: BridgeBleSession? = null
    private var pendingConnectId: Any? = null
    private var disposed = false

    /**
     * Whether we've told the app it's in a live-control session. Drives the
     * native movable FAB colour via [ApplicationStateHandler], exactly like
     * the cardboard editor (WebBleFragment): STATE_CONTROL while a peripheral
     * is connected, STATE_IDLE once it drops or the editor closes.
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

    /** Entry point from the WebSocket thread. */
    fun onMessage(text: String) {
        handler.post { if (!disposed) handle(text) }
    }

    /** Socket closed (or server stopping) — tear everything down. */
    fun dispose() {
        handler.post {
            if (disposed) return@post
            disposed = true
            stopScan()
            gatt?.disconnect()
            gatt = null
            // The disconnect callback will be stale (gatt is null), so reset
            // the FAB state here for the editor-closed / socket-closed case.
            reportIdle()
        }
    }

    // ---- JSON-RPC plumbing --------------------------------------------------

    private fun handle(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "bad json: ${e.message}")
            return
        }
        if (!json.has("method")) return // we never issue requests, ignore responses
        val method = json.optString("method")
        val params = json.optJSONObject("params") ?: JSONObject()
        val id: Any? = json.opt("id")
        try {
            when (method) {
                "getVersion" -> sendResult(id, JSONObject().put("protocol", "1.3"))
                "discover" -> handleDiscover(params, id)
                "connect" -> handleConnect(params, id)
                "write" -> handleWrite(params, id)
                "read" -> handleRead(params, id)
                "startNotifications" -> handleStartNotifications(params, id)
                "stopNotifications" -> handleStopNotifications(params, id)
                "getServices" -> sendResult(id, JSONArray(allowedServices.map { it.toString() }))
                "pingMe" -> sendResult(id, "willPing")
                else -> sendError(id, -32601, "unknown method: $method")
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method failed: ${e.message}")
            sendError(id, -32500, e.message ?: "internal error")
        }
    }

    private fun send(json: JSONObject) {
        try {
            if (socket.isOpen) socket.send(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    private fun sendResult(id: Any?, result: Any?) {
        if (id == null) return
        send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result ?: JSONObject.NULL),
        )
    }

    private fun sendError(id: Any?, code: Int, message: String) {
        Log.w(TAG, "error($code): $message")
        if (id == null) return
        send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)),
        )
    }

    private fun sendNotification(method: String, params: JSONObject) {
        send(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params),
        )
    }

    private fun closeSocket(reason: String) {
        try {
            if (socket.isOpen) socket.close(1000, reason)
        } catch (_: Exception) {
        }
    }

    // ---- discover ------------------------------------------------------------

    private fun handleDiscover(params: JSONObject, id: Any?) {
        if (!hasScanPermission()) {
            sendError(id, -32500, "Bluetooth permission not granted")
            return
        }
        if (!Utils.isBluetoothEnabled()) {
            sendError(id, -32500, "Bluetooth is disabled")
            return
        }
        // Pre-S, BLE scan results only arrive when location services are on;
        // otherwise startScan succeeds silently and nothing is ever found.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !Utils.isLocationEnabled(appCtx)) {
            sendError(id, -32500, "Location services are disabled")
            return
        }
        val parsed = parseFilters(params)
        if (parsed.isEmpty() || parsed.all { it.isTrivial }) {
            sendError(id, -32602, "discovery request requires at least one non-trivial filter")
            return
        }
        // A repeated discover restarts the session cleanly. Disconnecting the
        // old GATT fires a stale onDisconnected later, but the per-session
        // listener staleness check drops it, so the socket survives.
        gatt?.disconnect()
        gatt = null
        reportIdle()
        filters = parsed
        allowedServices.clear()
        parsed.forEach { allowedServices.addAll(it.services) }
        params.optJSONArray("optionalServices")?.let { arr ->
            for (i in 0 until arr.length()) {
                resolveUuid(arr.opt(i))?.let(allowedServices::add)
            }
        }
        discovered.clear()
        lastAdvertised.clear()
        if (startScan()) {
            Log.i(TAG, "discover: ${parsed.size} filter(s), allowed services=$allowedServices — scanning")
            sendResult(id, JSONObject.NULL)
        } else {
            sendError(id, -32500, "failed to start BLE scan")
        }
    }

    private fun parseFilters(params: JSONObject): List<Filter> {
        val out = ArrayList<Filter>()
        val arr = params.optJSONArray("filters") ?: return out
        for (i in 0 until arr.length()) {
            val fo = arr.optJSONObject(i) ?: continue
            val services = ArrayList<UUID>()
            fo.optJSONArray("services")?.let { sArr ->
                for (j in 0 until sArr.length()) {
                    resolveUuid(sArr.opt(j))?.let(services::add)
                }
            }
            val mfg = ArrayList<MfgFilter>()
            fo.optJSONObject("manufacturerData")?.let { md ->
                for (key in md.keys()) {
                    val mfgId = key.toIntOrNull() ?: continue
                    val entry = md.optJSONObject(key)
                    mfg.add(
                        MfgFilter(
                            id = mfgId,
                            dataPrefix = entry?.optJSONArray("dataPrefix")?.toByteArray(),
                            mask = entry?.optJSONArray("mask")?.toByteArray(),
                        ),
                    )
                }
            }
            out.add(
                Filter(
                    name = fo.optString("name").takeIf { fo.has("name") && it.isNotEmpty() },
                    namePrefix = fo.optString("namePrefix")
                        .takeIf { fo.has("namePrefix") && it.isNotEmpty() },
                    services = services,
                    manufacturer = mfg,
                ),
            )
        }
        return out
    }

    private fun JSONArray.toByteArray(): ByteArray =
        ByteArray(length()) { i -> optInt(i).toByte() }

    // ---- BLE scan --------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { if (!disposed) onAdvertisement(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { if (!disposed) results.forEach { onAdvertisement(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            // The discover response was already sent, so the only way to tell
            // the page the scan died (e.g. SCAN_FAILED_SCANNING_TOO_FREQUENTLY
            // after 5 starts/30s) is to close the socket — scratch-vm maps
            // that to its scan-error UI instead of a forever-empty picker.
            handler.post {
                if (disposed) return@post
                Log.w(TAG, "scan failed: $errorCode")
                scanning = false
                closeSocket("scan failed ($errorCode)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(): Boolean {
        stopScan()
        val manager = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            // No hardware filters: namePrefix can't be expressed there, so
            // matching happens in software in onAdvertisement().
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        val manager = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        try {
            manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun onAdvertisement(result: ScanResult) {
        if (!scanning) return
        if (filters.none { matches(it, result) }) return
        val device = result.device ?: return
        val peripheralId = device.address ?: return
        // Throttle repeat advertisements so the socket isn't flooded.
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastAdvertised[peripheralId]
        if (last != null && now - last < ADVERT_THROTTLE_MS) return
        lastAdvertised[peripheralId] = now
        discovered[peripheralId] = device
        val name = result.scanRecord?.deviceName ?: deviceNameSafe(device)
        Log.d(TAG, "didDiscoverPeripheral $peripheralId '${name ?: ""}' rssi=${result.rssi}")
        sendNotification(
            "didDiscoverPeripheral",
            JSONObject()
                .put("peripheralId", peripheralId)
                .put("name", name ?: "")
                .put("rssi", result.rssi),
        )
    }

    @SuppressLint("MissingPermission")
    private fun deviceNameSafe(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (_: SecurityException) {
            null
        }

    private fun matches(f: Filter, result: ScanResult): Boolean {
        val record = result.scanRecord
        val advName = record?.deviceName ?: deviceNameSafe(result.device)
        if (f.name != null && f.name != advName) return false
        if (f.namePrefix != null && (advName == null || !advName.startsWith(f.namePrefix))) return false
        if (f.services.isNotEmpty()) {
            val advertised = record?.serviceUuids?.map { it.uuid }?.toSet() ?: emptySet()
            if (!advertised.containsAll(f.services)) return false
        }
        for (m in f.manufacturer) {
            val data = record?.getManufacturerSpecificData(m.id) ?: return false
            val prefix = m.dataPrefix ?: continue
            if (data.size < prefix.size) return false
            for (i in prefix.indices) {
                val mask = m.mask?.getOrNull(i)?.toInt() ?: 0xFF
                if ((data[i].toInt() and mask) != (prefix[i].toInt() and mask)) return false
            }
        }
        return true
    }

    // ---- connect ---------------------------------------------------------------

    private fun handleConnect(params: JSONObject, id: Any?) {
        val peripheralId = params.optString("peripheralId")
        val device = discovered[peripheralId]
        if (device == null) {
            sendError(id, -32602, "unknown peripheralId: $peripheralId")
            return
        }
        stopScan()
        Log.i(TAG, "connect $peripheralId (${deviceNameSafe(device) ?: "?"})")
        // Drop any previous peripheral before taking a new one — its stale
        // listener will be ignored (see listenerFor staleness check).
        gatt?.disconnect()
        pendingConnectId = id
        // The listener needs a reference to its own session, which only
        // exists after construction — capture it through a holder var.
        var session: BridgeBleSession? = null
        val created = BridgeBleSession(appCtx, listenerFor { session })
        session = created
        gatt = created
        created.connect(device)
    }

    /**
     * A GATT listener bound to one [BridgeBleSession]. Every callback checks
     * whether that session is still the current one; a stale session (already
     * replaced by a newer connect, or cleared by discover/dispose) is ignored
     * so its late disconnect can't tear down a healthy socket.
     */
    private fun listenerFor(owner: () -> BridgeBleSession?) = object : BridgeBleSession.Listener {
        private fun isStale(): Boolean {
            val o = owner()
            return o == null || gatt !== o
        }

        override fun onConnected(deviceName: String?) {
            handler.post {
                if (isStale()) return@post
                reportControl()
                pendingConnectId?.let {
                    pendingConnectId = null
                    sendResult(it, JSONObject.NULL)
                }
            }
        }

        override fun onDisconnected(reason: String) {
            handler.post {
                if (isStale()) return@post
                reportIdle()
                pendingConnectId?.let {
                    pendingConnectId = null
                    sendError(it, -32500, "connect failed: $reason")
                }
                // Peripheral went away — closing the socket is how the
                // scratch-vm client learns about it (handleDisconnectError).
                closeSocket("peripheral disconnected")
            }
        }

        override fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
            handler.post {
                if (isStale()) return@post
                sendNotification(
                    "characteristicDidChange",
                    JSONObject()
                        .put("serviceId", serviceUuid.toString())
                        .put("characteristicId", characteristicUuid.toString())
                        .put("message", Base64.encodeToString(data, Base64.NO_WRAP))
                        .put("encoding", "base64"),
                )
            }
        }

        override fun onError(message: String) {
            handler.post {
                if (isStale()) return@post
                reportIdle()
                pendingConnectId?.let {
                    pendingConnectId = null
                    sendError(it, -32500, message)
                }
            }
        }
    }

    // ---- GATT ops -----------------------------------------------------------------

    private fun handleWrite(params: JSONObject, id: Any?) {
        val g = connectedGatt(id) ?: return
        val chr = resolveUuid(params.opt("characteristicId"))
            ?: run { sendError(id, -32602, "unknown characteristicId"); return }
        val svc = resolveService(params, chr, id) ?: return
        val data = decodeMessage(params) ?: run { sendError(id, -32602, "bad message"); return }
        // Mirror Scratch Link: an omitted withResponse uses write-without-
        // response only if the characteristic supports it, else with-response.
        val withResponse = if (params.has("withResponse")) {
            params.optBoolean("withResponse")
        } else {
            g.canWriteWithoutResponse(svc, chr)?.not() ?: true
        }
        g.write(svc, chr, data, withResponse) { ok ->
            handler.post {
                if (ok) sendResult(id, data.size) else sendError(id, -32500, "write failed")
            }
        }
    }

    private fun handleRead(params: JSONObject, id: Any?) {
        val g = connectedGatt(id) ?: return
        val chr = resolveUuid(params.opt("characteristicId"))
            ?: run { sendError(id, -32602, "unknown characteristicId"); return }
        val svc = resolveService(params, chr, id) ?: return
        val doRead = {
            g.read(svc, chr) { data ->
                handler.post {
                    if (data != null) {
                        sendResult(
                            id,
                            JSONObject()
                                .put("message", Base64.encodeToString(data, Base64.NO_WRAP))
                                .put("encoding", "base64"),
                        )
                    } else {
                        sendError(id, -32500, "read failed")
                    }
                }
            }
        }
        if (params.optBoolean("startNotifications", false)) {
            // Fail the whole read if arming notifications fails, else the
            // client believes it is subscribed and waits forever.
            g.enableNotify(svc, chr) { ok ->
                if (ok) {
                    doRead()
                } else {
                    handler.post { sendError(id, -32500, "startNotifications failed") }
                }
            }
        } else {
            doRead()
        }
    }

    private fun handleStartNotifications(params: JSONObject, id: Any?) {
        val g = connectedGatt(id) ?: return
        val chr = resolveUuid(params.opt("characteristicId"))
            ?: run { sendError(id, -32602, "unknown characteristicId"); return }
        val svc = resolveService(params, chr, id) ?: return
        g.enableNotify(svc, chr) { ok ->
            handler.post {
                if (ok) sendResult(id, JSONObject.NULL) else sendError(id, -32500, "startNotifications failed")
            }
        }
    }

    private fun handleStopNotifications(params: JSONObject, id: Any?) {
        val g = connectedGatt(id) ?: return
        val chr = resolveUuid(params.opt("characteristicId"))
            ?: run { sendError(id, -32602, "unknown characteristicId"); return }
        val svc = resolveService(params, chr, id) ?: return
        g.disableNotify(svc, chr) { ok ->
            handler.post {
                if (ok) sendResult(id, JSONObject.NULL) else sendError(id, -32500, "stopNotifications failed")
            }
        }
    }

    private fun connectedGatt(id: Any?): BridgeBleSession? {
        val g = gatt
        if (g == null || !g.isConnected) {
            sendError(id, -32500, "peripheral is not connected")
            return null
        }
        return g
    }

    // ---- helpers -----------------------------------------------------------------

    /**
     * Resolve the service for a GATT op and enforce the access policy: the
     * service must be in [allowedServices] (the discover filters +
     * optionalServices), matching desktop Scratch Link's GATT blocklist.
     * serviceId is optional — when omitted we pick the allowed service that
     * actually holds [chr] on the connected peripheral. Sends an error and
     * returns null on failure.
     */
    private fun resolveService(params: JSONObject, chr: UUID, id: Any?): UUID? {
        val v = params.opt("serviceId")
        if (v != null && v != JSONObject.NULL) {
            val s = resolveUuid(v) ?: run { sendError(id, -32602, "unknown serviceId"); return null }
            if (!allowedServices.contains(s)) {
                sendError(id, -32602, "serviceId not permitted: $s")
                return null
            }
            return s
        }
        val g = gatt
        val match = allowedServices.firstOrNull { g?.hasCharacteristic(it, chr) == true }
            ?: allowedServices.singleOrNull()
        if (match == null) {
            sendError(id, -32602, "could not resolve service for characteristic $chr")
        }
        return match
    }

    /**
     * Resolve a protocol UUID value: JSON number (16/32-bit Bluetooth alias),
     * full UUID string, or short hex alias string.
     */
    private fun resolveUuid(v: Any?): UUID? = when (v) {
        is Number -> uuidFromAlias(v.toLong())
        is String -> when {
            v.contains('-') -> runCatching { UUID.fromString(v.lowercase()) }.getOrNull()
            v.matches(Regex("^[0-9a-fA-F]{4}$|^[0-9a-fA-F]{8}$")) -> uuidFromAlias(v.toLong(16))
            else -> null
        }
        else -> null
    }

    private fun uuidFromAlias(alias: Long): UUID =
        UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", alias))

    private fun decodeMessage(params: JSONObject): ByteArray? {
        if (!params.has("message")) return null
        val message = params.optString("message")
        return when (params.optString("encoding", "")) {
            "base64" -> try {
                Base64.decode(message, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                null
            }
            // Default per protocol: message is a Unicode string.
            else -> message.toByteArray(Charsets.UTF_8)
        }
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Permission.isAccessGranted(appCtx, *Permission.BLUETOOTH_PERMISSIONS)
        } else {
            Permission.isAccessGranted(appCtx, *Permission.LOCATION_PERMISSIONS)
        }

    companion object {
        private const val TAG = "ScratchLinkBle"
        private const val ADVERT_THROTTLE_MS = 700L
    }
}
