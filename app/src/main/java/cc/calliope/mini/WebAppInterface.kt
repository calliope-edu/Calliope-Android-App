package cc.calliope.mini

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

class WebAppInterface(private val context: Context) {

    private val bluetoothService = BluetoothService(context)

    @JavascriptInterface
    fun connectToDevice(): Boolean {
        return bluetoothService.connect("D8:0F:45:61:FE:65")
    }

    @JavascriptInterface
    fun sendData(data: String) {
        bluetoothService.sendData(data)
    }

    @JavascriptInterface
    fun receiveData(): String? {
        return bluetoothService.receiveData()
    }

    @JavascriptInterface
    fun disconnect() {
        bluetoothService.disconnect()
    }
}