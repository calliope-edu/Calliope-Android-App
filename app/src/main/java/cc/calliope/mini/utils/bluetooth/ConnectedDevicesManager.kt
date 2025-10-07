package cc.calliope.mini.utils.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import cc.calliope.mini.utils.Constants
import androidx.core.content.edit

class ConnectedDevicesManager(private val context: Context) {

    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    fun getConnectedAddresses(): List<String> {
        val addresses = preferences.getString(Constants.CONNECTED_DEVICE_ADDRESSES, "") ?: ""
        return if (addresses.isEmpty()) emptyList() else addresses.split(",")
    }

    fun saveCurrentDevice(address: String, pattern: String) {
        preferences.edit {
            putString(Constants.CURRENT_DEVICE_ADDRESS, address)
            putString(Constants.CURRENT_DEVICE_PATTERN, pattern)

            val addresses = getConnectedAddresses().toMutableList()
            if (!addresses.contains(address)) {
                addresses.add(address)
                putString(Constants.CONNECTED_DEVICE_ADDRESSES, addresses.joinToString(","))
            }

        }
    }

    fun removeCurrentDevice(address: String) {
        if (address.isEmpty()) return

        preferences.edit {

            remove(Constants.CURRENT_DEVICE_ADDRESS)
            remove(Constants.CURRENT_DEVICE_PATTERN)

            val newAddresses = getConnectedAddresses().filterNot { it == address }
            putString(Constants.CONNECTED_DEVICE_ADDRESSES, newAddresses.joinToString(","))

        }
    }

    fun getCurrentAddress(): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString(Constants.CURRENT_DEVICE_ADDRESS, "") ?: ""
    }

    fun getCurrentPattern(): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return preferences.getString(Constants.CURRENT_DEVICE_PATTERN, "ZUZUZ") ?: "ZUZUZ"
    }

    fun removeAllDevices() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return

        getConnectedAddresses().forEach { address ->
            val device = bluetoothAdapter.getRemoteDevice(address)
            BluetoothUtils.removeBond(device)
        }

        preferences.edit().apply {
            remove(Constants.CONNECTED_DEVICE_ADDRESSES)
            remove(Constants.CURRENT_DEVICE_ADDRESS)
            remove(Constants.CURRENT_DEVICE_PATTERN)
            apply()
        }
    }

    fun removeBond(address: String): Boolean {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val device = bluetoothAdapter.getRemoteDevice(address)
        return BluetoothUtils.removeBond(device)
    }
}