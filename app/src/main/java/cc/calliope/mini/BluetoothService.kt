package cc.calliope.mini

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothService(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val uuid: UUID = UUID.fromString("0b500100-607f-4151-9091-7d008d6ffc5c")

    @RequiresPermission(allOf = [android.Manifest.permission.BLUETOOTH_CONNECT])
    fun connect(address: String): Boolean {
        if (bluetoothAdapter == null) {
            Toast.makeText(context, "Bluetooth не підтримується", Toast.LENGTH_SHORT).show()
            return false
        }

        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(address)

        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
            bluetoothSocket?.connect()
            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream
            Toast.makeText(context, "Підключено до $address", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(context, "Не вдалося підключитися", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    fun sendData(data: String) {
        try {
            outputStream?.write(data.toByteArray())
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun receiveData(): String? {
        val buffer = ByteArray(1024)
        return try {
            val bytes = inputStream?.read(buffer)
            bytes?.let { String(buffer, 0, it) }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    fun disconnect() {
        try {
            bluetoothSocket?.close()
            inputStream?.close()
            outputStream?.close()
            Toast.makeText(context, "Роз'єднано", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}