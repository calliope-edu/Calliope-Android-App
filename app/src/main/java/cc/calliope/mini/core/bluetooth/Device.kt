package cc.calliope.mini.core.bluetooth

import android.os.Build
import android.os.SystemClock
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResultData
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResults

class Device(bleScanResults: BleScanResults) {
    companion object {
        private val RELEVANT_LIMIT: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 5 else 10 // Секунди
    }

    private var serverDevice: ServerDevice = bleScanResults.device
    private var bleScanResultData: BleScanResultData? = bleScanResults.lastScanResult

    fun getAddress(): String {
        return serverDevice.address
    }

    fun getName(): String? {
        return bleScanResultData?.scanRecord?.deviceName
    }

    fun isBonded(): Boolean {
        return serverDevice.isBonded
    }

    fun getPattern(): String {
        val pattern = "[a-zA-Z :]+\\[([A-Z]{5})]".toRegex()
        val name = getName() ?: return ""
        return pattern.find(name.uppercase())?.groupValues?.get(1) ?: ""
    }

    fun isActual(): Boolean {
        bleScanResultData?.let {
            val timeSinceBoot = nanosecondsToSeconds(SystemClock.elapsedRealtimeNanos())
            val timeSinceScan = nanosecondsToSeconds(it.timestampNanos)
            return timeSinceBoot - timeSinceScan < RELEVANT_LIMIT
        }
        return false
    }

    private fun nanosecondsToSeconds(nanoseconds: Long): Double {
        return nanoseconds / 1_000_000_000.0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val device = other as Device
        return getAddress() == device.getAddress()
    }

    override fun toString(): String {
        return "Device(address=${getAddress()}, name=${getName()}, pattern=${getPattern()}, isBonded=${isBonded()}, isActual=${isActual()})"
    }
}
