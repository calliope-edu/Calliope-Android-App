package cc.calliope.mini.core.service.partialflashing

import android.util.Log
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * A class to manipulate micro:bit hex files.
 * Focused towards stripping a file down to its PXT section for use in Partial Flashing.
 *
 * Based on original work:
 * (c) 2017 - 2021, Micro:bit Educational Foundation and contributors
 * SPDX-License-Identifier: MIT
 */
class HexUtils(filePath: String) {

    companion object {
        private const val TAG = "HexUtils"

        const val STATUS_INIT = 0
        const val STATUS_INVALID_FILE = 1
        const val STATUS_NO_PARTIAL_FLASH = 2

        /**
         * Convert hex record to byte array for partial flashing command
         */
        fun recordToByteArray(hexString: String, offset: Int, packetNum: Int): ByteArray {
            val len = hexString.length
            val data = ByteArray((len / 2) + 4)

            for (i in 0 until len step 2) {
                data[(i / 2) + 4] = ((Character.digit(hexString[i], 16) shl 4) +
                        Character.digit(hexString[i + 1], 16)).toByte()
            }

            // WRITE Command
            data[0] = 0x01
            data[1] = (offset shr 8).toByte()
            data[2] = (offset and 0xFF).toByte()
            data[3] = (packetNum and 0xFF).toByte()

            return data
        }
    }

    var status: Int = STATUS_INIT
        private set

    private val hexLines: MutableList<String> = mutableListOf()

    init {
        try {
            if (!openHexFile(filePath)) {
                status = STATUS_INVALID_FILE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: $e")
            status = STATUS_INVALID_FILE
        }
    }

    private fun openHexFile(filePath: String): Boolean {
        return try {
            FileInputStream(filePath).use { fis ->
                BufferedReader(InputStreamReader(fis)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        hexLines.add(line)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: $e")
            false
        }
    }

    fun numOfLines(): Int = hexLines.size

    fun searchForData(search: String): Int {
        hexLines.forEachIndexed { index, line ->
            if (line.contains(search)) {
                return index
            }
        }
        return -1
    }

    fun searchForDataRegEx(search: String): Int {
        val regex = Regex(search)
        hexLines.forEachIndexed { index, line ->
            if (regex.containsMatchIn(line)) {
                return index
            }
        }
        return -1
    }

    fun searchForAddress(address: Long): Int {
        var lastBaseAddr = 0L

        hexLines.forEachIndexed { index, line ->
            when (getRecordType(line)) {
                2 -> { // Extended Segment Address
                    val data = getRecordData(line)
                    if (data.length != 4) return -1

                    val hi = data.substring(0, 1).toInt(16)
                    val lo = data.substring(1).toInt(16)
                    lastBaseAddr = hi.toLong() * 0x1000L + lo.toLong() * 0x10L
                }
                4 -> { // Extended Linear Address
                    val data = getRecordData(line)
                    if (data.length != 4) return -1

                    lastBaseAddr = data.toInt(16).toLong() * 0x10000L
                }
                0, 0x0D -> { // Data record
                    if (address - lastBaseAddr in 0 until 0x10000) {
                        val a = lastBaseAddr + getRecordAddress(line)
                        val n = getRecordDataLength(line) / 2 // bytes

                        if (a <= address && a + n > address) {
                            return index
                        }
                    }
                }
            }
        }
        return -1
    }

    fun getDataFromIndex(index: Int): String = getRecordData(hexLines[index])

    fun getRecordTypeFromIndex(index: Int): Int = getRecordType(hexLines[index])

    fun getRecordAddressFromIndex(index: Int): Int = getRecordAddress(hexLines[index])

    fun getRecordDataLengthFromIndex(index: Int): Int = getRecordDataLength(hexLines[index])

    fun getSegmentAddress(index: Int): Int {
        var cur = index
        while (cur >= 0) {
            if (getRecordTypeFromIndex(cur) == 4) {
                return getRecordData(hexLines[cur]).toInt(16)
            }
            cur--
        }
        return 0
    }

    private fun getRecordAddress(record: String): Int {
        return try {
            record.substring(3, 7).toInt(16)
        } catch (e: Exception) {
            Log.e(TAG, "getRecordAddress error: $e")
            0
        }
    }

    private fun getRecordDataLength(record: String): Int {
        return try {
            2 * record.substring(1, 3).toInt(16)
        } catch (e: Exception) {
            Log.e(TAG, "getRecordDataLength error: $e")
            0
        }
    }

    private fun getRecordType(record: String): Int {
        if (record.length < 9) return -1
        return try {
            record.substring(7, 9).toInt(16)
        } catch (e: Exception) {
            Log.e(TAG, "getRecordType error: $e")
            -1
        }
    }

    private fun getRecordData(record: String): String {
        return try {
            val len = getRecordDataLength(record)
            record.substring(9, 9 + len)
        } catch (e: Exception) {
            Log.e(TAG, "getRecordData error: $e")
            ""
        }
    }
}
