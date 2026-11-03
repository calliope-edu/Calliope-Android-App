package cc.calliope.mini.core.hex

/**
 * One line of an Intel HEX file, split into its fields.
 *
 * `:LLAAAATT<data>CC` — LL byte count, AAAA 16-bit address, TT record type,
 * then `LL` data bytes and a checksum. The data stays as the hex text it was
 * read as ([dataHex]); the partial-flashing protocol addresses it in nibbles
 * and only the DFU image builder ever needs the bytes ([data]).
 *
 * Record types beyond the Intel standard are the Microsoft Universal Hex
 * block records (0x0A–0x0D) and MakeCode's embedded-source record (0x0E).
 * Types 0x00 and 0x0D both carry flash contents — see [isData].
 */
class IntelHexRecord private constructor(
    val byteCount: Int,
    /** The 16-bit address field; the full address needs the current base, see [IntelHexFile]. */
    val address: Int,
    val type: Int,
    /** Data bytes as upper-case hex, two characters per byte. */
    val dataHex: String,
    val checksum: Int,
) {
    val isData: Boolean get() = isData(type)

    /** True when the checksum field matches the record contents. */
    val checksumValid: Boolean
        get() {
            var sum = byteCount + (address shr 8) + (address and 0xFF) + type
            for (i in dataHex.indices step 2) {
                sum += dataHex.substring(i, i + 2).toInt(16)
            }
            return (-sum and 0xFF) == checksum
        }

    fun data(): ByteArray = ByteArray(byteCount) { i ->
        dataHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

    companion object {
        const val TYPE_DATA = 0x00
        const val TYPE_END_OF_FILE = 0x01
        const val TYPE_EXTENDED_SEGMENT_ADDRESS = 0x02
        const val TYPE_EXTENDED_LINEAR_ADDRESS = 0x04
        const val TYPE_UHEX_BLOCK_START = 0x0A
        const val TYPE_UHEX_BLOCK_END = 0x0B
        const val TYPE_UHEX_PADDED_DATA = 0x0C
        const val TYPE_UHEX_CUSTOM_DATA = 0x0D
        const val TYPE_UHEX_OTHER_DATA = 0x0E

        /** Flash contents: a plain data record or a Universal Hex custom-data record. */
        fun isData(type: Int): Boolean = type == TYPE_DATA || type == TYPE_UHEX_CUSTOM_DATA

        /**
         * Parses one line, or returns null when it is not a well-formed
         * record (no leading colon, truncated, non-hex characters). The
         * checksum is not verified here; ask [checksumValid].
         */
        fun parse(line: String): IntelHexRecord? {
            if (line.length < 11 || line[0] != ':') return null
            val byteCount = line.substring(1, 3).toIntOrNull(16) ?: return null
            val dataEnd = 9 + byteCount * 2
            if (line.length < dataEnd + 2) return null
            val address = line.substring(3, 7).toIntOrNull(16) ?: return null
            val type = line.substring(7, 9).toIntOrNull(16) ?: return null
            val dataHex = line.substring(9, dataEnd).uppercase()
            if (!dataHex.all { it in '0'..'9' || it in 'A'..'F' }) return null
            val checksum = line.substring(dataEnd, dataEnd + 2).toIntOrNull(16) ?: return null
            return IntelHexRecord(byteCount, address, type, dataHex, checksum)
        }
    }
}
