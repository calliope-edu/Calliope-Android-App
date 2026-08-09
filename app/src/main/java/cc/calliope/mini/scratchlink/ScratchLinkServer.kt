package cc.calliope.mini.scratchlink

import android.content.Context
import android.net.Uri
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Loopback Scratch Link server.
 *
 * Scratch-based editors (blocks.calliope.cc) fall back to the Scratch Link
 * protocol when `navigator.bluetooth` is unavailable — which is always the
 * case inside Android WebView (`scratch-vm/src/io/ble.js` exports
 * `navigator.bluetooth ? WebBLE : BLE`). The fallback opens a JSON-RPC 2.0
 * WebSocket to ws://127.0.0.1:20111/scratch/ble and drives BLE through it.
 * This server answers that socket and bridges the protocol to native BLE,
 * so scratch extensions (LEGO WeDo2/Boost, Vernier gdxfor, micro:bit, …)
 * work inside the WebView with no page modification at all.
 *
 * Security model (mirrors desktop Scratch Link):
 *  - Binds 127.0.0.1 only — no LAN exposure.
 *  - Requires a WebSocket `Origin` header matching an allowed scratch editor
 *    origin. Browsers cannot forge Origin, so this blocks drive-by pages in
 *    other on-device browsers and any page the WebView navigates to that
 *    isn't a scratch editor. (A malicious native app could still spoof the
 *    header — the residual risk inherent to any loopback bridge.)
 *
 * The server is started lazily the first time a scratch editor is opened and
 * kept for the process lifetime: a single idle loopback selector thread is
 * cheap, and never re-binding avoids the stop/start port race entirely.
 *
 * Protocol: scratch-link/Documentation/BluetoothLE.md
 */
class ScratchLinkServer private constructor(private val appCtx: Context) :
    WebSocketServer(InetSocketAddress(LOOPBACK, PORT)) {

    private val sessions = ConcurrentHashMap<WebSocket, ScratchLinkBleSession>()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val origin = handshake.getFieldValue("Origin")
        if (!isAllowedOrigin(origin)) {
            Log.w(TAG, "rejected connection from origin=\"$origin\"")
            conn.close(POLICY_VIOLATION, "forbidden origin")
            return
        }
        val path = handshake.resourceDescriptor?.substringBefore('?')?.trimEnd('/')
        if (path == BLE_PATH) {
            Log.i(TAG, "session open (BLE) origin=$origin")
            sessions[conn] = ScratchLinkBleSession(appCtx, conn)
        } else {
            // Bluetooth Classic (/scratch/bt — EV3 etc.) is not implemented.
            // Refuse so the editor surfaces a scan error rather than hanging.
            conn.close(NORMAL, "unsupported session type")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        sessions[conn]?.onMessage(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        sessions.remove(conn)?.dispose()
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn != null) {
            Log.w(TAG, "ws error: ${ex.message}")
            sessions.remove(conn)?.dispose()
        } else {
            // Server-level failure — most likely the port is already bound
            // (another app squatting 20111). The editor's picker won't work;
            // log loudly so it's diagnosable.
            Log.e(TAG, "server error (bind failed?): ${ex.message}")
            bindFailed = true
        }
    }

    override fun onStart() {
        Log.i(TAG, "Scratch Link server listening on $LOOPBACK:$PORT")
    }

    private fun closeSessions(reason: String) {
        if (sessions.isEmpty()) return
        Log.i(TAG, "closing ${sessions.size} session(s): $reason")
        for (conn in sessions.keys.toList()) {
            sessions.remove(conn)?.dispose()
            runCatching { conn.close(NORMAL, reason) }
        }
    }

    private fun isAllowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrEmpty()) return false
        val uri = runCatching { Uri.parse(origin) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val scheme = uri.scheme?.lowercase()
        return scheme == "https" && SCRATCH_HOSTS.contains(host)
    }

    companion object {
        private const val TAG = "ScratchLinkServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val PORT = 20111
        private const val BLE_PATH = "/scratch/ble"
        private const val NORMAL = 1000
        private const val POLICY_VIOLATION = 1008

        /**
         * Hosts whose editors speak the Scratch Link protocol. Single source
         * of truth for both the start gate and the Origin allowlist.
         */
        private val SCRATCH_HOSTS = setOf("blocks.calliope.cc")

        @Volatile
        private var instance: ScratchLinkServer? = null

        @Volatile
        var bindFailed = false
            private set

        /** True if [url]'s host is a Scratch-based editor. */
        @JvmStatic
        fun isScratchEditorUrl(url: String?): Boolean {
            if (url.isNullOrEmpty()) return false
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
            return SCRATCH_HOSTS.contains(host)
        }

        /**
         * End every open session, dropping the BLE link each one holds.
         *
         * Called when the page that owned them goes away. Destroying a WebView
         * closes its socket, and the socket closing is what releases the
         * peripheral — but a page that lingers keeps the device connected, and
         * a connected Calliope stops advertising, so the next page can never
         * find it. Saying it explicitly makes that release deterministic
         * instead of dependent on when the old WebView happens to die.
         */
        @JvmStatic
        fun closeAllSessions(reason: String) {
            instance?.closeSessions(reason)
        }

        /**
         * Start the loopback server once. Idempotent and safe to call from
         * the main thread (WebSocketServer.start() spawns its own thread).
         * Only call for scratch editors — gate with [isScratchEditorUrl].
         */
        @JvmStatic
        @Synchronized
        fun start(context: Context) {
            if (instance != null) return
            try {
                val server = ScratchLinkServer(context.applicationContext)
                server.isReuseAddr = true
                server.start()
                instance = server
            } catch (e: Exception) {
                Log.e(TAG, "failed to start: ${e.message}")
                bindFailed = true
            }
        }
    }
}
