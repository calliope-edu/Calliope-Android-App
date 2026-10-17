package cc.calliope.mini.core.hex

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * The part of a hex file that a partial flash sends: the user's program,
 * found behind the MakeCode (PXT) or MicroPython layout header, together
 * with the hash of the runtime it was built against.
 *
 * Pure file knowledge — no radio. [batches] turns the program into the
 * exact write packets of the micro:bit partial-flashing protocol, so the
 * transport only has to number them, send them four at a time and wait
 * for the acknowledgement.
 *
 * Based on original work:
 * (c) 2017 - 2021, Micro:bit Educational Foundation and contributors
 * SPDX-License-Identifier: MIT
 */
class PartialFlashImage private constructor(
    private val hex: IntelHexFile,
    /** Line of the first program record. */
    val startLine: Int,
    /** Where the program begins inside that record's data, in hex characters. */
    val startNibble: Int,
    /** Flash address of the program's first byte; the board's own layout must agree. */
    val codeStartAddress: Long,
    /** Hash of the runtime the program was built against, 16 hex characters; null when the file carries none. */
    val runtimeHash: String?,
    val isMicroPython: Boolean,
) {

    /** One write of the protocol: [offset] goes in the header, [payloadHex] is the data (16 bytes, or less at the end of a record). */
    class Packet(val offset: Int, val payloadHex: String) {
        /** `0x01 offsetHi offsetLo packetNum data…` */
        fun encode(packetNum: Int): ByteArray {
            val data = ByteArray(payloadHex.length / 2 + 4)
            data[0] = FLASH_COMMAND
            data[1] = (offset shr 8).toByte()
            data[2] = (offset and 0xFF).toByte()
            data[3] = (packetNum and 0xFF).toByte()
            for (i in payloadHex.indices step 2) {
                data[i / 2 + 4] = payloadHex.substring(i, i + 2).toInt(16).toByte()
            }
            return data
        }
    }

    /**
     * Four packets the board acknowledges together. The first carries the
     * low 16 bits of the batch's flash address, the second the high 16
     * bits, the rest offset 0. [linesSent] is how many program records
     * were complete when the last packet was built — the progress figure.
     */
    class Batch(val packets: List<Packet>, val linesSent: Int)

    /** Program records from [startLine] to the first non-data record; never less than 1. */
    val dataLineCount: Int = run {
        var count = 0
        var line = startLine
        while (line < hex.size && hex.isData(line)) {
            count++
            line++
        }
        count.coerceAtLeast(1)
    }

    /**
     * The whole program as protocol batches. Records are cut into 16-byte
     * payloads; the last batch is completed with `0xFF` padding packets.
     */
    fun batches(): List<Batch> {
        val batches = mutableListOf<Batch>()
        var current = mutableListOf<Packet>()
        var line = startLine
        var nibble = startNibble
        var linesSent = 0
        var batchAddressLo = 0
        var batchAddressHi = 0
        var padding = false

        while (true) {
            if (!padding && (line >= hex.size || !hex.isData(line))) {
                if (current.isEmpty()) break
                padding = true
            }

            val dataHex: String
            val payload: String
            val address: Long
            if (padding) {
                dataHex = PADDING
                payload = PADDING
                address = 0
            } else {
                dataHex = hex.dataHex(line)
                address = hex.address(line)
                payload = if (nibble + PAYLOAD_NIBBLES > dataHex.length) {
                    dataHex.substring(nibble)
                } else {
                    dataHex.substring(nibble, nibble + PAYLOAD_NIBBLES)
                }
            }

            val offset = when (current.size) {
                0 -> {
                    val batchAddress = address + nibble / 2
                    batchAddressLo = (batchAddress and 0xFFFF).toInt()
                    batchAddressHi = (batchAddress ushr 16).toInt()
                    batchAddressLo
                }
                1 -> batchAddressHi
                else -> 0
            }
            current.add(Packet(offset, payload))

            if (current.size == BATCH_SIZE) {
                batches.add(Batch(current, linesSent))
                current = mutableListOf()
                if (padding) break
            }

            if (!padding) {
                nibble += payload.length
                if (nibble >= dataHex.length) {
                    nibble = 0
                    line++
                    linesSent++
                }
            }
        }
        return batches
    }

    /** A byte position in the file: a record and an even offset into its hex text. */
    private class Cursor(val line: Int, val nibble: Int)

    companion object {
        private const val FLASH_COMMAND: Byte = 0x01
        private const val BATCH_SIZE = 4
        private const val PAYLOAD_NIBBLES = 32
        private const val PADDING = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"

        /** Header that MakeCode (PXT) places in front of the program; the runtime hash follows it. */
        private const val PXT_MAGIC = "708E3B92C615A841C49866C975EE5197"

        /** MicroPython layout-table header: magic1, 8 bytes of fields, magic2. */
        private const val UPY_MAGIC1 = "FE307F59"
        private val UPY_HEADER = Regex(".*FE307F59.{16}9DD7B1C1.*")
        private const val UPY_REGION_RUNTIME = 2
        private const val UPY_REGION_PROGRAM = 3

        /**
         * Finds the program in [hex] for a board with [layout]: MakeCode
         * first, then MicroPython. Null when the file has neither header
         * or the header does not add up, in which case a full flash is the
         * only option.
         */
        fun locate(hex: IntelHexFile, layout: FlashLayout): PartialFlashImage? =
            locateMakeCode(hex) ?: locateMicroPython(hex, layout)

        fun locateMakeCode(hex: IntelHexFile): PartialFlashImage? {
            val line = hex.indexOf(PXT_MAGIC)
            if (line < 0) return null
            val nibble = hex.dataHex(line).indexOf(PXT_MAGIC)
            if (nibble < 0) return null

            val headerAddress = hex.address(line) + nibble / 2
            val hashCursor = hex.cursorAt(headerAddress + PXT_MAGIC.length / 2) ?: return null
            val hash = hex.readHex(hashCursor, 8)
            if (hash.length < 16) return null

            return PartialFlashImage(hex, line, nibble, headerAddress, hash, isMicroPython = false)
        }

        fun locateMicroPython(hex: IntelHexFile, layout: FlashLayout): PartialFlashImage? {
            val line = hex.indexOf(UPY_HEADER)
            if (line < 0) return null
            val nibble = hex.dataHex(line).indexOf(UPY_MAGIC1)
            if (nibble < 0) return null

            val header = hex.readHex(Cursor(line, nibble), 16)
            if (header.length < 32) return null
            val version = header.uint16(8)
            val tableLength = header.uint16(12)
            val regionCount = header.uint16(16)
            val pageLog2 = header.uint16(20)
            if (version != 1) return null
            if (tableLength != regionCount * 16) return null
            if (1 shl pageLog2 != layout.pageSize) return null

            // The region table sits right before the header.
            val headerAddress = hex.address(line) + nibble / 2
            var runtimeHash: String? = null
            var codeStart = -1L
            var codeLength = -1L
            for (i in 0 until regionCount) {
                val cursor = hex.cursorAt(headerAddress - tableLength + i * 16) ?: return null
                val region = hex.readHex(cursor, 16)
                if (region.length < 32) return null

                val id = region.uint8(0)
                val hashType = region.uint8(2)
                val startPage = region.uint16(4)
                val length = region.uint32(8)
                val hash: String? = when (hashType) {
                    0 -> null
                    1 -> region.substring(16, 32)
                    2 -> hex.hashOfStringAt(region.uint32(16))
                    else -> return null
                }
                when (id) {
                    UPY_REGION_RUNTIME -> runtimeHash = hash
                    UPY_REGION_PROGRAM -> {
                        codeStart = startPage.toLong() * layout.pageSize
                        codeLength = length
                    }
                }
            }
            if (codeStart < 0 || codeLength < 0) return null

            val code = hex.cursorAt(codeStart) ?: return null
            return PartialFlashImage(hex, code.line, code.nibble, codeStart, runtimeHash, isMicroPython = true)
        }

        /**
         * Hash type 2: the region's hash is the CRC32 of a C string found at
         * [address], as 8 little-endian bytes. A hex that does not contain
         * that string (a program-only hex without the runtime) has no hash.
         */
        private fun IntelHexFile.hashOfStringAt(address: Long): String? {
            val cursor = cursorAt(address) ?: return null
            val text = readHex(cursor, 100)
            if (text.isEmpty()) return null
            var length = 0
            while (length < text.length / 2 && text.uint8(length * 2) != 0) length++
            val bytes = ByteArray(length) { text.uint8(it * 2).toByte() }
            val crc = CRC32().also { it.update(bytes) }.value
            val hashBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(crc).array()
            return hashBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        }

        private fun IntelHexFile.cursorAt(address: Long): Cursor? {
            val line = indexOfAddress(address)
            if (line < 0) return null
            return Cursor(line, ((address - address(line)) * 2).toInt())
        }

        /** [byteCount] bytes of data as hex text starting at [cursor], continuing across data records. */
        private fun IntelHexFile.readHex(cursor: Cursor, byteCount: Int): String {
            val out = StringBuilder()
            var line = cursor.line
            var nibble = cursor.nibble
            var remaining = byteCount * 2
            while (remaining > 0 && line < size) {
                if (!isData(line)) {
                    line++
                    nibble = 0
                    continue
                }
                val data = dataHex(line)
                val chunk = minOf(data.length - nibble, remaining)
                if (chunk > 0) {
                    out.append(data, nibble, nibble + chunk)
                    nibble += chunk
                    remaining -= chunk
                }
                if (remaining > 0 && nibble >= data.length) {
                    line++
                    nibble = 0
                }
            }
            return out.toString()
        }

        private fun String.uint8(nibble: Int): Int = substring(nibble, nibble + 2).toInt(16)

        private fun String.uint16(nibble: Int): Int = uint8(nibble) or (uint8(nibble + 2) shl 8)

        private fun String.uint32(nibble: Int): Long =
            uint16(nibble).toLong() or (uint16(nibble + 4).toLong() shl 16)
    }
}
