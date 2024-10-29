package cc.calliope.mini

import java.io.BufferedReader
import java.io.File
import java.util.LinkedList

class HexParser(private val url: String) {

//    private fun calcChecksum(address: UInt, type: UByte, data: ByteArray): UByte {
//        var crc: UInt = data.size.toUByte() +
//                (address shr 8).toUByte() +
//                (address.toUShort().toUByte() + type)
//        for (b in data) {
//            crc += b.toUByte()
//        }
//        return (0x100u - crc.toUInt()).toUByte()
//    }
//
//    enum class HexVersion(val value: String) {
//        V3(":1000000000040020810A000015070000610A0000BA"),
//        V2(":020000040000FA"),
//        UNIVERSAL(":0400000A9900C0DEBB"),
//        INVALID("");
//
//        companion object {
//            val allValues = values().toList()
//        }
//    }

//    fun getHexVersion(): Set<HexVersion> {
//        val file = File(url)
//        val reader = file.bufferedReader()
//
//        val relevantLines = mutableSetOf<String>()
//        reader.useLines { lines ->
//            val iterator = lines.iterator()
//            if (iterator.hasNext()) relevantLines.add(iterator.next().trim())
//            if (iterator.hasNext()) relevantLines.add(iterator.next().trim())
//        }
//
//        val enumSet = mutableSetOf<HexVersion>()
//        for (version in HexVersion.allValues) {
//            if (relevantLines.contains(version.value)) {
//                enumSet.add(version)
//            }
//        }
//        if (enumSet.isEmpty()) {
//            enumSet.add(HexVersion.INVALID)
//        }
//        return enumSet
//    }

    fun parse(handleDataEntry: (Long, ByteArray, Int, Boolean) -> Unit) {
        val file = File(url)
        val reader = file.bufferedReader()

        var isUniversal = false
        var addressHi: UInt = 0u
        var dataType = 0

        reader.useLines { lines ->
            lines.forEach { line ->
                var beginIndex = 0
                var endIndex = beginIndex + 1

                if (line.isEmpty() || line[beginIndex] != ':') return@forEach
                beginIndex = endIndex

                endIndex = beginIndex + 2
                val length = line.substring(beginIndex, endIndex).toInt(16).toUByte()
                beginIndex = endIndex

                endIndex = beginIndex + 4
                val addressLo = line.substring(beginIndex, endIndex).toUInt(16)
                beginIndex = endIndex

                endIndex = beginIndex + 2
                val type = line.substring(beginIndex, endIndex).toInt(16)
                beginIndex = endIndex

                endIndex = beginIndex + 2 * length.toInt()
                if (endIndex > line.length) return@forEach
                val payload = line.substring(beginIndex, endIndex)
                beginIndex = endIndex

                when (type) {
                    0, 13 -> { // Data
                        val position = addressHi + addressLo
                        val data = payload.hexStringToByteArray()
                        if (data.size == length.toInt()) {
                            handleDataEntry(position.toLong(), data, dataType, isUniversal)
                        }
                    }
                    1 -> return // EOF
                    2 -> { // EXT SEGMENT ADDRESS
                        val segment = payload.toUInt(16)
                        addressHi = segment shl 4
                    }
                    3 -> { /* START SEGMENT ADDRESS */ }
                    4 -> { // EXT LINEAR ADDRESS
                        val segment = payload.toUInt(16)
                        addressHi = segment shl 16
                    }
                    5 -> { /* START LINEAR ADDRESS */ }
                    10 -> { // Block Start Address
                        isUniversal = true
                        val dataTypeField = line.substring(9, 13)
                        dataType = when (dataTypeField) {
                            "9900" -> 1
                            "9903" -> 2
                            else -> dataType
                        }
                    }
                    else -> { /* Інші типи */ }
                }
            }
        }
    }

//    fun retrievePartialFlashingInfo(): Triple<ByteArray, ByteArray, PartialFlashData>? {
//        val file = File(url)
//        val reader = file.bufferedReader()
//
//        val (magicLine, currentSegmentAddress) = forwardToMagicNumber(reader) ?: return null
//
//        val hashesLine = reader.readLine() ?: return null
//        if (hashesLine.length >= 41) {
//            val templateHash = hashesLine.substring(9, 25).hexStringToByteArray()
//            val programHash = hashesLine.substring(25, 41).hexStringToByteArray()
//            val partialFlashData = PartialFlashData(
//                nextLines = listOf(hashesLine, magicLine),
//                currentSegmentAddress = currentSegmentAddress,
//                reader = reader,
//                lineCount = countLinesToFlash(reader)
//            )
//            return Triple(templateHash, programHash, partialFlashData)
//        }
//        return null
//    }

//    private fun forwardToMagicNumber(reader: BufferedReader): Pair<String, UInt>? {
//        var magicLine: String? = null
//        var currentSegmentAddress: UInt = 0u
//
//        reader.lineSequence().forEach { line ->
//            if (HexReader.isMagicStart(line)) {
//                magicLine = line
//                return@forEach
//            } else {
//                val type = HexReader.typeOf(line)
//                if (type == 4) {
//                    val segmentAddress = HexReader.readSegmentAddress(line)
//                    if (segmentAddress != null) {
//                        currentSegmentAddress = segmentAddress.toUInt()
//                    }
//                }
//            }
//        }
//
//        return if (magicLine != null) Pair(magicLine!!, currentSegmentAddress) else null
//    }

//    private fun countLinesToFlash(reader: BufferedReader): Int {
//        var numLinesToFlash = 0
//        reader.lineSequence().forEach { line ->
//            if (HexReader.isEndOfFileOrMagicEnd(line)) {
//                return numLinesToFlash
//            }
//            if (line.startsWith(":") && HexReader.typeOf(line) == 0) {
//                numLinesToFlash += 1
//            }
//        }
//        return numLinesToFlash
//    }
}
//
//class PartialFlashData(
//    private val nextLines: List<String>,
//    private var currentSegmentAddress: UInt,
//    private val reader: BufferedReader,
//    val lineCount: Int
//) : Sequence<Pair<UInt, ByteArray>>, Iterator<Pair<UInt, ByteArray>> {
//
//    private val nextData = LinkedList<Pair<UInt, ByteArray>>()
//
//    init {
//        nextLines.forEach { read(it) }
//    }
//
//    override fun iterator(): Iterator<Pair<UInt, ByteArray>> = this
//
//    override fun hasNext(): Boolean {
//        if (nextData.isNotEmpty()) {
//            return true
//        }
//        readNextData()
//        return nextData.isNotEmpty()
//    }
//
//    override fun next(): Pair<UInt, ByteArray> {
//        if (!hasNext()) {
//            throw NoSuchElementException()
//        }
//        return nextData.poll()
//    }
//
//    private fun readNextData() {
//        val line = reader.readLine() ?: return
//        read(line)
//    }
//
//    private fun read(record: String) {
//        if (HexReader.isEndOfFileOrMagicEnd(record)) {
//            reader.close()
//            return
//        }
//        when (HexReader.typeOf(record)) {
//            0 -> { // Data record
//                if (record.contains("00000001FF")) {
//                    return
//                } else {
//                    val data = HexReader.readData(record)
//                    if (data != null) {
//                        val address = currentSegmentAddress + data.first
//                        nextData.add(Pair(address, data.second))
//                    }
//                }
//            }
//            2, 4 -> { // Extended segment address
//                val segmentAddress = HexReader.readSegmentAddress(record)
//                if (segmentAddress != null) {
//                    currentSegmentAddress = segmentAddress.toUInt()
//                }
//            }
//            else -> { /* Інші типи */ }
//        }
//    }
//}

//object HexReader {
//
//    const val MAGIC_START_NUMBER = "708E3B92C615A841C49866C975EE5197"
//    const val MAGIC_END_NUMBER = "41140E2FB82FA2B"
//    const val EOF_NUMBER = "00000001FF"
//
//    fun readSegmentAddress(record: String): UInt? {
//        val length = lengthOf(record)
//        if (length == 2 && validate(record, length)) {
//            val data = dataOf(record, length) ?: return null
//            return ((data[0].toUByte().toUInt() shl 8) or data[1].toUByte().toUInt())
//        }
//        return null
//    }
//
//    fun readData(record: String): Pair<UInt, ByteArray>? {
//        val length = lengthOf(record) ?: return null
//        if (validate(record, length)) {
//            val address = addressOf(record) ?: return null
//            val data = dataOf(record, length) ?: return null
//            return Pair(address, data)
//        }
//        return null
//    }
//
//    fun validate(record: String, length: Int): Boolean {
//        return record.trim().length == 9 + 2 * length + 2
//    }
//
//    fun typeOf(record: String): Int? {
//        return try {
//            record.substring(7, 9).toInt(16)
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    fun lengthOf(record: String): Int? {
//        return try {
//            record.substring(1, 3).toInt(16)
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    fun addressOf(record: String): UInt? {
//        return try {
//            record.substring(3, 7).toUInt(16)
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    fun dataOf(record: String, length: Int): ByteArray? {
//        val endIndex = 9 + 2 * length
//        if (record.length < endIndex) return null
//        return record.substring(9, endIndex).hexStringToByteArray()
//    }
//
//    fun isMagicStart(record: String): Boolean {
//        return record.length >= 41 && record.substring(9, 41) == MAGIC_START_NUMBER
//    }
//
//    fun isEndOfFileOrMagicEnd(record: String): Boolean {
//        return (record.length >= 24 && record.substring(9, 24) == MAGIC_END_NUMBER) || record.contains(
//            EOF_NUMBER
//        )
//    }
//}

fun String.hexStringToByteArray(): ByteArray {
    val len = this.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val byte = this.substring(i, i + 2).toInt(16).toByte()
        data[i / 2] = byte
        i += 2
    }
    return data
}
