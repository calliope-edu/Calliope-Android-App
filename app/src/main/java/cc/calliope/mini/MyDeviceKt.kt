package cc.calliope.mini

import android.os.SystemClock
import cc.calliope.mini.utils.Version
import no.nordicsemi.android.kotlin.ble.core.ServerDevice
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResultData
import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResults

class MyDeviceKt(bleScanResults: BleScanResults) {
    companion object {
        private val RELEVANT_LIMIT: Int = if (Version.VERSION_O_AND_NEWER) 5 else 10 // Секунди
    }

    private var serverDevice: ServerDevice = bleScanResults.device
    private var bleScanResultData: BleScanResultData? = bleScanResults.lastScanResult

    fun getAddress(): String {
        return serverDevice.address
    }

    fun getName(): String {
        return serverDevice.name
    }

    fun isBonded(): Boolean {
        return serverDevice.isBonded
    }

    fun getPattern(): String {
        val pattern = "[a-zA-Z :]+\\[([A-Z]{5})]".toRegex()
        return pattern.find(serverDevice.name.uppercase())?.groupValues?.get(1) ?: ""
    }

    fun getNumPattern(): String {
        return getPattern().map { LetterMapping.getNumber(it) }.joinToString("")
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
}
