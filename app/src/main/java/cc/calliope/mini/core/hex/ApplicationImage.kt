package cc.calliope.mini.core.hex

/**
 * Cuts the application binary for a full (DFU) flash out of a hex file.
 *
 * Takes the data records inside the board's [FlashLayout.applicationRange],
 * skipping records with a bad checksum. In a Microsoft Universal Hex only
 * the block for this board (by [FlashLayout.universalBlockType]) is taken;
 * a plain hex is taken whole. Gaps between records are filled with `0xFF`,
 * and the result is padded to a multiple of four bytes.
 */
object ApplicationImage {

    private const val ERASED: Byte = 0xFF.toByte()

    /** A contiguous run of bytes at [address]. */
    private class Run(val address: Long, val data: MutableList<Byte> = mutableListOf()) {
        val end: Long get() = address + data.size
    }

    @JvmStatic
    fun extract(hex: IntelHexFile, layout: FlashLayout): ByteArray {
        val runs = mutableListOf<Run>()
        var isUniversal = false
        var blockMatches = false

        for ((address, record) in hex.records()) {
            if (!record.checksumValid) continue
            when (record.type) {
                IntelHexRecord.TYPE_END_OF_FILE -> break
                IntelHexRecord.TYPE_UHEX_BLOCK_START -> {
                    // The first two data bytes name the board the block is for.
                    isUniversal = true
                    if (record.byteCount >= 2) {
                        when (record.dataHex.substring(0, 4).toInt(16)) {
                            layout.universalBlockType -> blockMatches = true
                            FlashLayout.NRF51.universalBlockType,
                            FlashLayout.NRF52.universalBlockType -> blockMatches = false
                        }
                    }
                }
                IntelHexRecord.TYPE_DATA, IntelHexRecord.TYPE_UHEX_CUSTOM_DATA -> {
                    if (address !in layout.applicationRange) continue
                    if (isUniversal && !blockMatches) continue
                    val last = runs.lastOrNull()
                    if (last == null || address != last.end) {
                        runs.add(Run(address))
                    }
                    runs.last().data.addAll(record.data().asList())
                }
            }
        }

        return join(runs.sortedBy { it.address })
    }

    private fun join(runs: List<Run>): ByteArray {
        val image = mutableListOf<Byte>()
        runs.zipWithNext { current, next ->
            image.addAll(current.data)
            val gap = (next.address - current.end).toInt()
            if (gap > 0) image.addAll(List(gap) { ERASED })
        }
        runs.lastOrNull()?.let { image.addAll(it.data) }
        val padding = (4 - image.size % 4) % 4
        image.addAll(List(padding) { ERASED })
        return image.toByteArray()
    }
}
