package cc.calliope.mini.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.util.Log
import cc.calliope.mini.BondingService

class BluetoothUtils {
    companion object {
        @JvmStatic
        fun removeBond(device: BluetoothDevice): Boolean {
            Utils.log(Log.WARN, BondingService.TAG, "Unpairing device...")
            return try {
                val removeBondMethod = device.javaClass.getMethod("removeBond")
                val result = removeBondMethod.invoke(device) as Boolean
                Log.d("removeBond", "Unpairing successful: $result")
                result
            } catch (e: Exception) {
                Log.e("removeBond", "Exception occurred during unpairing", e)
                false
            }
        }

        @JvmStatic
        fun clearServicesCache(gatt: BluetoothGatt) {
            try {
                val refresh = gatt.javaClass.getMethod("refresh")
                val success = refresh.invoke(gatt) as Boolean
                Utils.log(Log.DEBUG, BondingService.TAG, "Refreshing result: $success")
            } catch (e: Exception) {
                Utils.log(
                    Log.ERROR,
                    BondingService.TAG,
                    "An exception occurred while refreshing device. $e"
                )
            }
        }

        fun isValidBluetoothMAC(macAddress: String?): Boolean {
            if (macAddress == null) {
                Utils.log(Log.ERROR, BondingService.TAG, "MAC address is null")
                return false
            }

            val regex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"
            return if (macAddress.matches(regex.toRegex())) {
                Utils.log(Log.INFO, BondingService.TAG, "Valid Bluetooth MAC address: $macAddress")
                true
            } else {
                Utils.log(
                    Log.INFO,
                    BondingService.TAG,
                    "Invalid Bluetooth MAC address: $macAddress"
                )
                false
            }
        }
    }
}