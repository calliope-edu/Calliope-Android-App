package cc.calliope.mini.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS-interface entrypoint for the Calliope native-proxy.
 *
 * One `@JavascriptInterface` method — `postMessage(json)` — so the same
 * envelope shape works on both Android and iOS (see
 * `mini-connection-widget/src/native-bridge.ts`). The bridge owns no state
 * directly; it parses the envelope and hands off to a [BridgeController]
 * tied to the hosting fragment's lifecycle.
 *
 * Envelope (web → native):
 *
 *     { "id": "...", "op": "connect|disconnect|flash|gattRead|gattWrite|
 *                            gattSubscribe|gattUnsubscribe|serialWrite",
 *       "args": { ... } }
 *
 * `op` and `id` are required; `args` defaults to `{}` if missing.
 */
class CalliopeProxyBridge(private val controller: BridgeController) {

    @JavascriptInterface
    fun postMessage(envelope: String?) {
        if (envelope.isNullOrEmpty()) return
        val parsed = try {
            JSONObject(envelope)
        } catch (e: Exception) {
            Log.w(TAG, "malformed envelope: ${e.message}")
            return
        }
        val id = parsed.optString("id")
        val op = parsed.optString("op")
        if (id.isEmpty() || op.isEmpty()) {
            Log.w(TAG, "envelope missing id/op")
            return
        }
        val args = parsed.optJSONObject("args") ?: JSONObject()
        controller.dispatch(id, op, args)
    }

    companion object {
        const val TAG = "CalliopeProxyBridge"
        /** The JavaScript-side global name. Must match `getBridge()` in
         *  the widget's `native-bridge.ts`. */
        const val JS_NAME = "CalliopeNative"
    }
}
