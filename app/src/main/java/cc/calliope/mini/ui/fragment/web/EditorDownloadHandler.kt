package cc.calliope.mini.ui.fragment.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import cc.calliope.mini.R
import cc.calliope.mini.utils.file.FileUtils
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

/**
 * Turns what an editor offers for download — a base64 data URL, a
 * `data:text/hex` URL, a plain hex string or an http(s) link — into a hex
 * file in the editor's directory.
 *
 * Decoding, disk and network all run on a worker thread; the outcome is
 * delivered on the main thread. (This used to happen on the main thread
 * under `StrictMode.permitAll()`.)
 */
class EditorDownloadHandler(context: Context, private val editorName: String?) {

    /** Delivered on the main thread; exactly one of the two per request. */
    interface Callback {
        fun onSaved(file: File)
        fun onFailed(messageRes: Int)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile
    private var closed = false

    /** A `data:…;base64,…` URL (blob downloads arrive like this). */
    fun saveBase64DataUrl(dataUrl: String, name: String, callback: Callback) = save(name, callback) { file ->
        file.writeBytes(Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT))
    }

    /** A hex program as text (MakeCode controller mode). */
    fun saveHexText(hex: String?, name: String, callback: Callback) = save(name, callback) { file ->
        require(!hex.isNullOrEmpty()) { "empty hex" }
        file.writeText(hex, Charsets.UTF_8)
    }

    /** Whatever a non-blob download URL turns out to be. */
    fun saveFromUrl(url: String, callback: Callback) = save(FileUtils.getFileName(url), callback) { file ->
        when {
            url.startsWith("data:text/hex") -> file.writeText(url.substringAfter(','), Charsets.UTF_8)
            url.startsWith("data:") && url.contains("base64") ->
                file.writeBytes(Base64.decode(url.substringAfter(','), Base64.DEFAULT))
            URLUtil.isValidUrl(url) && url.endsWith(".hex") -> download(url, file)
            else -> throw IllegalArgumentException("unsupported download url")
        }
    }

    /** Stop delivering results (the view is gone). Running work finishes silently. */
    fun close() {
        closed = true
        worker.shutdown()
    }

    private fun save(name: String, callback: Callback, write: (File) -> Unit) {
        if (closed) return
        worker.execute {
            val file = FileUtils.getFile(appContext, editorName, name)
            if (file == null) {
                Log.e(TAG, "could not create a file for $name")
                deliver { callback.onFailed(R.string.error_snackbar_save_file_error) }
                return@execute
            }
            try {
                write(file)
                Log.i(TAG, "saved $file")
                deliver { callback.onSaved(file) }
            } catch (e: Exception) {
                Log.e(TAG, "saving $name failed", e)
                file.delete()
                deliver { callback.onFailed(R.string.error_snackbar_download_error) }
            }
        }
    }

    private fun download(link: String, file: File) {
        val connection = URL(link).openConnection().apply {
            readTimeout = READ_TIMEOUT_MS
            connectTimeout = CONNECT_TIMEOUT_MS
        }
        connection.getInputStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun deliver(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private companion object {
        const val TAG = "EditorDownloadHandler"
        const val READ_TIMEOUT_MS = 5000
        const val CONNECT_TIMEOUT_MS = 10000
    }
}
