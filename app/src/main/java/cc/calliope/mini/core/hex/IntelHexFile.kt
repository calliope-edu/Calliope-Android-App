package cc.calliope.mini.core.hex

import java.io.File
import java.io.Reader

/**
 * An Intel HEX file held in memory, addressable by line.
 *
 * Every line is kept, parsed or not, so line indices are stable and match
 * the file on disk — the partial-flashing code walks the file by line and
 * nibble, and that is how its positions are reported in logs. Lines that
 * are not records read as type [NO_RECORD] with empty data.
 *
 * Absolute addresses are resolved up front: each data record's [address]
 * is its 16-bit field plus the base set by the last Extended Linear
 * (0x04) or Extended Segment (0x02) address record before it.
 */
class IntelHexFile private constructor(private val lines: List<String>) {

    private val records: Array<IntelHexRecord?> = Array(lines.size) { IntelHexRecord.parse(lines[it]) }

    /** Base address in force at each line — the upper part of its absolute address. */
    private val bases: LongArray = LongArray(lines.size).also { bases ->
        var base = 0L
        for (i in records.indices) {
            val record = records[i]
            if (record != null && record.byteCount == 2) {
                when (record.type) {
                    IntelHexRecord.TYPE_EXTENDED_LINEAR_ADDRESS -> base = record.dataHex.toLong(16) shl 16
                    IntelHexRecord.TYPE_EXTENDED_SEGMENT_ADDRESS -> base = record.dataHex.toLong(16) shl 4
                }
            }
            bases[i] = base
        }
    }

    val size: Int get() = lines.size

    fun record(index: Int): IntelHexRecord? = records[index]

    /** Record type at [index], or [NO_RECORD] for a line that is not one. */
    fun type(index: Int): Int = records[index]?.type ?: NO_RECORD

    fun isData(index: Int): Boolean = records[index]?.isData == true

    /** Data of the record at [index] as hex text, empty for a non-record line. */
    fun dataHex(index: Int): String = records[index]?.dataHex ?: ""

    /** Absolute address of the first data byte of the record at [index]. */
    fun address(index: Int): Long = bases[index] + (records[index]?.address ?: 0)

    /** Index of the first line containing [text], or -1. */
    fun indexOf(text: String, from: Int = 0): Int {
        for (i in from until lines.size) {
            if (lines[i].contains(text)) return i
        }
        return -1
    }

    /** Index of the first line matching [regex], or -1. */
    fun indexOf(regex: Regex, from: Int = 0): Int {
        for (i in from until lines.size) {
            if (regex.containsMatchIn(lines[i])) return i
        }
        return -1
    }

    /** Index of the data record that holds the byte at [address], or -1. */
    fun indexOfAddress(address: Long): Int {
        for (i in records.indices) {
            val record = records[i] ?: continue
            if (!record.isData) continue
            val start = bases[i] + record.address
            if (address >= start && address < start + record.byteCount) return i
        }
        return -1
    }

    /** All records, with their absolute addresses, in file order; non-record lines skipped. */
    fun records(): Sequence<Pair<Long, IntelHexRecord>> = sequence {
        for (i in records.indices) {
            val record = records[i] ?: continue
            yield((bases[i] + record.address) to record)
        }
    }

    companion object {
        /** [type] of a line that is not a record. */
        const val NO_RECORD = -1

        @JvmStatic
        fun read(path: String): IntelHexFile = File(path).bufferedReader().use { read(it) }

        fun read(reader: Reader): IntelHexFile = IntelHexFile(reader.readLines())

        fun of(lines: List<String>): IntelHexFile = IntelHexFile(lines.toList())
    }
}
