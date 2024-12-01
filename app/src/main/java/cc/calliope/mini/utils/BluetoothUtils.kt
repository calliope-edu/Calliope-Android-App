package cc.calliope.mini.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.util.Log

class BluetoothUtils {
    companion object {
        const val TAG = "BluetoothUtils"
        @JvmStatic
        fun removeBond(device: BluetoothDevice): Boolean {
            Log.d(TAG, "Unpairing device...")
            return try {
                val removeBondMethod = device.javaClass.getMethod("removeBond")
                val result = removeBondMethod.invoke(device) as Boolean
                Log.d(TAG, "Unpairing result: $result")
                result
            } catch (e: Exception) {
                Log.e(TAG, "An exception occurred while unpairing device. $e")
                false
            }
        }

        @JvmStatic
        fun clearServicesCache(gatt: BluetoothGatt) {
            Log.i(TAG, "Refreshing device cache...")
            try {
                val refresh = gatt.javaClass.getMethod("refresh")
                val success = refresh.invoke(gatt) as Boolean
                Log.d(TAG, "Refreshing result: $success")
            } catch (e: Exception) {
                Log.e(TAG, "An exception occurred while refreshing device. $e"
                )
            }
        }

        fun isValidBluetoothMAC(macAddress: String?): Boolean {
            if (macAddress == null) {
                Log.e(TAG, "MAC address is null")
                return false
            }

            val regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"
            return if (macAddress.matches(regex.toRegex())) {
                Log.d(TAG, "Valid Bluetooth MAC address: $macAddress")
                true
            } else {
                Log.w(TAG, "Invalid Bluetooth MAC address: $macAddress")
                false
            }
        }
    }
}