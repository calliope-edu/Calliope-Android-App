package cc.calliope.mini.core.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Line-oriented Nordic UART link with the board, for the Cardboard
 * editors. Mirrors the campus bridge session: one [GattConnection], the
 * UART TX characteristic subscribed, writes chunked to the negotiated MTU
 * and serialized through the connection's queue (which replaced the old
 * hand-rolled 12 ms write pacing).
 *
 * Callbacks arrive on the Bluetooth binder thread.
 */
class UartSession(
    appCtx: Context,
    private val listener: Listener,
) {

    interface Listener {
        /** GATT link up and services discovered (before UART is armed). */
        fun onConnected()

        /** UART notifications armed; [send] will reach the board. */
        fun onReady()

        /** Link down; [hadLink] tells whether it was ready before. */
        fun onDisconnected(status: Int, hadLink: Boolean)

        /** A UART frame from the board, decoded as UTF-8. */
        fun onText(text: String)
    }

    private val gattListener: GattConnection.Listener = object : GattConnection.Listener {
        override fun onConnected(deviceName: String?) {
            listener.onConnected()
            if (!connection.hasCharacteristic(BleUuids.UART_SERVICE, BleUuids.UART_TX) ||
                !connection.hasCharacteristic(BleUuids.UART_SERVICE, BleUuids.UART_RX)
            ) {
                Log.w(TAG, "no UART service on $deviceName — disconnecting")
                connection.disconnect()
                return
            }
            writeWithResponse = connection.canWriteWithoutResponse(BleUuids.UART_SERVICE, BleUuids.UART_RX) != true
            connection.enableNotify(BleUuids.UART_SERVICE, BleUuids.UART_TX) { ok ->
                // As before: a missing CCCD is not fatal, writes still work.
                if (!ok) Log.w(TAG, "could not enable UART notifications")
                if (!connection.isConnected) return@enableNotify
                ready = true
                listener.onReady()
            }
        }

        override fun onDisconnected(status: Int) {
            val hadLink = ready
            ready = false
            listener.onDisconnected(status, hadLink)
        }

        override fun onNotify(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray) {
            if (serviceUuid == BleUuids.UART_SERVICE && characteristicUuid == BleUuids.UART_TX) {
                listener.onText(String(data, StandardCharsets.UTF_8))
            }
        }

        override fun onError(message: String) {
            Log.w(TAG, "error: $message")
            if (connection.isConnected) connection.disconnect()
        }
    }

    private val connection: GattConnection = GattConnection(
        appCtx.applicationContext,
        gattListener,
        GattConnection.Config(refreshServiceCache = true),
    )

    @Volatile
    private var ready = false
    @Volatile
    private var writeWithResponse = false

    val isConnected: Boolean get() = connection.isConnected
    val isReady: Boolean get() = ready

    fun connect(device: BluetoothDevice) = connection.connect(device)

    fun disconnect() = connection.disconnect()

    /** Send [text] as one UART line (a trailing newline is added if missing). */
    fun send(text: String) {
        if (!ready) return
        val line = if (text.endsWith("\n")) text else "$text\n"
        connection.write(
            BleUuids.UART_SERVICE, BleUuids.UART_RX,
            line.toByteArray(StandardCharsets.UTF_8), writeWithResponse,
        ) { ok -> if (!ok) Log.w(TAG, "UART write failed") }
    }

    companion object {
        private const val TAG = "UartSession"
    }
}
