package cc.calliope.mini.core.hex

import java.io.File
import java.io.IOException

/**
 * Deep check of which boards a hex file is for, used when its first lines
 * are ambiguous (see `FileUtils.computeFileVersion`).
 */
object HexVersionProbe {

    /** First code address only an nRF52 can hold: nRF51 flash is 256 KB. */
    private const val V3_ONLY_FLASH_START = 0x40000L

    /** Above this lie UICR/FICR and peripherals, not code — no evidence either way. */
    private const val FLASH_REGION_END = 0x10000000L

    /**
     * True when the file provably runs on a mini 3: either it has flash
     * contents at an address an nRF51 does not have, or it carries
     * Microsoft Universal Hex block records (0x0A–0x0D), which only genuine
     * universal hexes do. MakeCode's embedded-source record (0x0E) appears
     * in plain V1/V2 hexes too and does not count.
     *
     * Streams the file and stops at the first hit; a read failure is "no
     * evidence".
     */
    @JvmStatic
    fun containsV3Evidence(path: String): Boolean {
        try {
            File(path).bufferedReader().useLines { lines ->
                var base = 0L
                for (line in lines) {
                    val record = IntelHexRecord.parse(line) ?: continue
                    when (record.type) {
                        IntelHexRecord.TYPE_EXTENDED_LINEAR_ADDRESS ->
                            if (record.byteCount == 2) base = record.dataHex.toLong(16) shl 16
                        IntelHexRecord.TYPE_DATA -> {
                            val address = base + record.address
                            if (address >= V3_ONLY_FLASH_START && address < FLASH_REGION_END) return true
                        }
                        in IntelHexRecord.TYPE_UHEX_BLOCK_START..IntelHexRecord.TYPE_UHEX_CUSTOM_DATA -> return true
                    }
                }
            }
        } catch (e: IOException) {
            return false
        }
        return false
    }
}
