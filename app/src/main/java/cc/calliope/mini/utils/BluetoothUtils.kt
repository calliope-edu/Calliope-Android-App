package cc.calliope.mini.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.util.Log

class BluetoothUtils {
    companion object {
        const val TAG = "BluetoothUtils"
        @JvmStatic
        fun removeBond(device: BluetoothDevice): Boolean {
            Utils.log(Log.DEBUG, TAG, "Unpairing device...")
            return try {
                val removeBondMethod = device.javaClass.getMethod("removeBond")
                val result = removeBondMethod.invoke(device) as Boolean
                Utils.log(Log.DEBUG, TAG, "Unpairing result: $result")
                result
            } catch (e: Exception) {
                Utils.log(Log.ERROR, TAG, "An exception occurred while unpairing device. $e")
                false
            }
        }

        @JvmStatic
        fun clearServicesCache(gatt: BluetoothGatt) {
            Utils.log(Log.INFO, TAG, "Refreshing device cache...")
            try {
                val refresh = gatt.javaClass.getMethod("refresh")
                val success = refresh.invoke(gatt) as Boolean
                Utils.log(Log.DEBUG, TAG, "Refreshing result: $success")
            } catch (e: Exception) {
                Utils.log(Log.ERROR, TAG, "An exception occurred while refreshing device. $e"
                )
            }
        }

        fun isValidBluetoothMAC(macAddress: String?): Boolean {
            if (macAddress == null) {
                Utils.log(Log.ERROR, TAG, "MAC address is null")
                return false
            }

            val regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"
            return if (macAddress.matches(regex.toRegex())) {
                Utils.log(Log.DEBUG, TAG, "Valid Bluetooth MAC address: $macAddress")
                true
            } else {
                Utils.log(Log.WARN, TAG, "Invalid Bluetooth MAC address: $macAddress")
                false
            }
        }
    }
}