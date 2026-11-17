package cc.calliope.mini.core.hex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32

class ApplicationImageTest {

    /**
     * Sizes and CRC32s of the images the previous extractor (`HexParser`)
     * produced for the bundled programs — the DFU payloads that have been
     * flashing real boards. Any change here changes what goes on the board.
     */
    @Test
    fun bundledProgramsProduceTheKnownImages() {
        assertImage("demo_matrix", FlashLayout.NRF51, 147456, "FBB146B8")
        assertImage("demo_matrix", FlashLayout.NRF52, 185632, "A6B8C3DA")
        assertImage("demo_effects", FlashLayout.NRF51, 141440, "5250B549")
        assertImage("demo_effects", FlashLayout.NRF52, 352260, "5E55E583")
        assertImage("demo_lofi_face", FlashLayout.NRF52, 195632, "9312DA6C")
        assertImage("one_time_pairing", FlashLayout.NRF51, 134064, "37385F94")
        assertImage("blocks", FlashLayout.NRF52, 195056, "384A35D2")
    }

    @Test
    fun fillsGapsWithErasedFlashAndPadsToFourBytes() {
        val hex = IntelHexFile.of(
            listOf(
                ":020000040001F9",                      // base 0x10000
                ":04C00000" + "11223344" + "00",        // @ 0x1C000 (checksums below are fixed up)
                ":02C00800" + "5566" + "00",            // @ 0x1C008 — 4-byte gap before it
                ":00000001FF",
            ).map(::withChecksum),
        )

        val image = ApplicationImage.extract(hex, FlashLayout.NRF52)

        assertArrayEquals(
            bytes(0x11, 0x22, 0x33, 0x44, 0xFF, 0xFF, 0xFF, 0xFF, 0x55, 0x66, 0xFF, 0xFF),
            image,
        )
    }

    @Test
    fun takesOnlyTheApplicationRangeAndSkipsBadChecksums() {
        val hex = IntelHexFile.of(
            listOf(
                withChecksum(":020000040000FA"),
                withChecksum(":0400000011111111" + "00"),  // SoftDevice area — out of range
                withChecksum(":020000040001F9"),
                withChecksum(":04C00000AAAAAAAA" + "00"),  // in range
                ":04C00400BBBBBBBB" + "00",                // in range but bad checksum
                withChecksum(":04C00800CCCCCCCC" + "00"),
                withChecksum(":020000040007F7"),
                withChecksum(":04300000DDDDDDDD" + "00"),  // 0x73000 — just past the range
                withChecksum(":00000001FF"),
            ),
        )

        val image = ApplicationImage.extract(hex, FlashLayout.NRF52)

        assertArrayEquals(
            bytes(0xAA, 0xAA, 0xAA, 0xAA, 0xFF, 0xFF, 0xFF, 0xFF, 0xCC, 0xCC, 0xCC, 0xCC),
            image,
        )
    }

    @Test
    fun universalHexYieldsOnlyTheBlockForTheBoard() {
        val hex = IntelHexFile.of(
            listOf(
                ":020000040000FA",
                ":0400000A9900C0DE" + "00",            // v1-class block
                ":020000040002F8",                     // base 0x20000 — in both ranges
                ":04000000" + "11111111" + "00",
                ":0400000B9900C0DE" + "00",            // block end (type 0x0B)
                ":0400000A9903C0DE" + "00",            // v2-class block
                ":020000040002F8",
                ":04000000" + "22222222" + "00",
                ":00000001FF",
            ).map(::withChecksum),
        )

        assertArrayEquals(bytes(0x11, 0x11, 0x11, 0x11), ApplicationImage.extract(hex, FlashLayout.NRF51))
        assertArrayEquals(bytes(0x22, 0x22, 0x22, 0x22), ApplicationImage.extract(hex, FlashLayout.NRF52))
    }

    private fun assertImage(name: String, layout: FlashLayout, size: Int, crc: String) {
        val image = ApplicationImage.extract(Fixtures.hex(name), layout)
        assertEquals("$name/$layout size", size, image.size)
        assertEquals("$name/$layout crc32", crc, "%08X".format(CRC32().also { it.update(image) }.value))
        assertEquals(0, image.size % 4)
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    /** Rewrites the last two characters of a record so its checksum is right. */
    private fun withChecksum(record: String): String {
        val body = record.substring(1, record.length - 2)
        var sum = 0
        for (i in body.indices step 2) sum += body.substring(i, i + 2).toInt(16)
        return ":" + body + "%02X".format(-sum and 0xFF)
    }
}
