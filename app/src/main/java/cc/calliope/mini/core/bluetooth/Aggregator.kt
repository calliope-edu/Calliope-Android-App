package cc.calliope.mini.core.bluetooth

import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResultData
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResults

class Aggregator {
    private val devices = mutableMapOf<ServerDevice, MutableList<BleScanResultData>>()
    private val results
        get() = devices.map { BleScanResults(it.key, it.value) }

    private fun aggregate(scanItem: BleScanResult): List<BleScanResults> {
        val data = scanItem.data
        devices.getOrPut(scanItem.device) { mutableListOf() }.let {
            if (data != null) it.add(data)
        }
        return results
    }

    fun aggregateDevices(scanItem: BleScanResult): List<DeviceKt> {
        aggregate(scanItem)
        return devices.map { DeviceKt(BleScanResults(it.key, it.value)) }
    }
}
