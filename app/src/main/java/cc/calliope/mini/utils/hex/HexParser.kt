package cc.calliope.mini.utils.hex

import cc.calliope.mini.utils.Constants.MINI_V2
import cc.calliope.mini.utils.Constants.MINI_V3
import java.io.File

class HexParser(private val path: String) {
    data class Partition(val address: UInt, val data: MutableList<Byte>)

    private fun calculateChecksum(address: UInt, type: Int, data: ByteArray): Int {
        var crc = data.size and 0xFF

        crc = (crc + ((address.toInt() shr 8) and 0xFF)) and 0xFF
        crc = (crc + (address.toInt() and 0xFF)) and 0xFF
        crc = (crc + type) and 0xFF

        for (b in data) {
            crc = (crc + (b.toInt() and 0xFF)) and 0xFF
        }

        crc = (0x100 - crc) and 0xFF
        return crc
    }

    fun getCalliopeBin(version: Int): ByteArray {
        val (addressRange, dataTypeCondition) = when (version) {
            MINI_V2 -> 0x18000u..0x3BFFFu to { dataType: Int -> dataType == 1 }
            MINI_V3 -> 0x1C000u..0x72FFFu to { dataType: Int -> dataType == 2 }
            else -> throw IllegalArgumentException("Unsupported version: $version")
        }

        val partitions = collectPartitions(addressRange, dataTypeCondition)

        return buildPaddedBin(partitions)
    }

    private fun collectPartitions(
        addressRange: UIntRange,
        dataTypeCondition: (Int) -> Boolean
    ): List<Partition> {
        val partitions = mutableListOf<Partition>()
        var lastAddress: UInt? = null

        parse { address, data, dataType, isUniversal ->
            if (address in addressRange && (dataTypeCondition(dataType) || !isUniversal)) {
                if (lastAddress == null || lastAddress!! > address || lastAddress!! + 16u < address) {
                    partitions.add(Partition(address, mutableListOf()))
                }
                partitions.lastOrNull()?.data?.addAll(data.asList())
                lastAddress = address
            }
        }

        return partitions.sortedBy { it.address }
    }

    private fun buildPaddedBin(partitions: List<Partition>): ByteArray {
        val paddedApplication = mutableListOf<Byte>()
        partitions.zipWithNext { current, next ->
            paddedApplication.addAll(current.data)
            val paddingSize = (next.address - (current.address + current.data.size.toUInt())).toInt()
            if (paddingSize > 0) {
                paddedApplication.addAll(ByteArray(paddingSize) { 0xFF.toByte() }.toList())
            }
        }

        if (partitions.isNotEmpty()) {
            paddedApplication.addAll(partitions.last().data)
        }

        val paddingSize = (4 - (paddedApplication.size % 4)) % 4
        paddedApplication.addAll(ByteArray(paddingSize) { 0xFF.toByte() }.toList())

        return paddedApplication.toByteArray()
    }

    private fun parse(handleDataEntry: (UInt, ByteArray, Int, Boolean) -> Unit) {
        val file = File(path)
        val reader = file.bufferedReader()
        var isUniversal = false
        var addressHi = 0u
        var dataType = 0

        reader.useLines { lines ->
            lines.forEach { line ->
                if (line.firstOrNull() != ':') return@forEach
                val length = line.substring(1, 3).toIntOrNull(16) ?: return@forEach
                val addressLo = line.substring(3, 7).toUIntOrNull(16) ?: return@forEach
                val type = line.substring(7, 9).toIntOrNull(16) ?: return@forEach
                val payload = line.substring(9, 9 + 2 * length)

                // Calculate checksum
                val checksum = line.substring(9 + 2 * length, 9 + 2 * length + 2).toIntOrNull(16) ?: return@forEach
                val calculatedChecksum = calculateChecksum(addressHi + addressLo.toUShort(), type, payload.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                if (checksum != calculatedChecksum) return@forEach

                when (type) {
                    0, 13 -> {
                        val position = addressHi + addressLo
                        val data = payload.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        if (data.size == length) {
                            handleDataEntry(position, data, dataType, isUniversal)
                        }
                    }
                    1 -> return
                    2 -> addressHi = payload.toUIntOrNull(16)?.shl(4) ?: 0u
                    4 -> addressHi = payload.toUIntOrNull(16)?.shl(16) ?: 0u
                    10 -> {
                        isUniversal = true
                        val dataTypeField = line.substring(9, 13)
                        dataType = when (dataTypeField) {
                            "9900" -> 1
                            "9903" -> 2
                            else -> dataType
                        }
                    }
                }
            }
        }
    }
}
