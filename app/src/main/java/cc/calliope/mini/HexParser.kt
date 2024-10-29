package cc.calliope.mini

import java.io.File

class HexParser(private val url: String) {
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
                    else -> { /* OTHER */ }
                }
            }
        }
    }
}

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
