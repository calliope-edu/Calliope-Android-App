package cc.calliope.mini.ui.fragment.web

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * The JavaScript the app injects into editor pages, kept as files under
 * `assets/web/` (readable, lintable) instead of Java string literals.
 */
object WebScripts {
    /** Captures blob download names; forwards MakeCode controller downloads. */
    const val DOWNLOAD_INTERCEPT = "download_intercept.js"
    /** Hides the Blocks startup connection modal before first paint. */
    const val CONNECTION_MODAL_HIDE = "connection_modal_hide.js"
    /**
     * Drives the Blocks (scratch) editor's BLE connection from the app side.
     * scratch-vm falls back to the Scratch Link protocol when
     * navigator.bluetooth is absent (always, in a WebView) and talks to our
     * in-app server; the script adds what scratch-gui only does on iPad —
     * auto-connect to the first peripheral found — suppresses the startup
     * connection modal, leaves a user-opened modal alone, and exposes
     * `window.__calliopeDisconnect` for the FAB's Disconnect.
     */
    const val SCRATCH_AUTO_CONNECT = "scratch_auto_connect.js"
    private const val BLOB_TO_BASE64 = "blob_to_base64.js"

    private val cache = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun load(context: Context, name: String): String = cache.getOrPut(name) {
        context.applicationContext.assets.open("web/$name").bufferedReader().use { it.readText() }
    }

    /** Script that reads [blobUrl] in the page and hands it to `Android.getBase64FromBlobData`. */
    @JvmStatic
    fun blobToBase64(context: Context, blobUrl: String, mimeType: String?, fallbackName: String): String =
        load(context, BLOB_TO_BASE64)
            .replace("__BLOB_URL__", jsEscape(blobUrl))
            .replace("__MIME_TYPE__", jsEscape(mimeType.orEmpty()))
            .replace("__FALLBACK_NAME__", jsEscape(fallbackName))

    /** Escape for the inside of a single-quoted JS string literal. */
    private fun jsEscape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\x3C")
                else -> append(c)
            }
        }
    }
}
