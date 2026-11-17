package cc.calliope.mini.core.hex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays real sessions: the packets built from the bundled hex files must
 * be, byte for byte, what the app sent to a mini 3 on 2026-10-20 — and the
 * hashes and code addresses must be the ones the board agreed with.
 */
class PartialFlashImageTest {

    @Test
    fun makeCodeProgramMatchesCapturedSession() {
        val image = PartialFlashImage.locate(Fixtures.hex("demo_matrix"), FlashLayout.NRF52)!!

        assertFalse(image.isMicroPython)
        assertEquals("D481A87B9AB258A4", image.runtimeHash)   // DAL hash the board reported for region 1
        assertEquals(0x47000L, image.codeStartAddress)        // start of region 2 on the board
        assertEquals(17911, image.startLine)
        assertEquals(0, image.startNibble)

        assertSendsCapturedPackets(image, "nrf52_makecode_demo_matrix.txt")
    }

    @Test
    fun microPythonProgramMatchesCapturedSession() {
        val image = PartialFlashImage.locate(Fixtures.hex("demo_effects"), FlashLayout.NRF52)!!

        assertTrue(image.isMicroPython)
        assertEquals("0EE8CB7600000000", image.runtimeHash)
        assertEquals(0x6D000L, image.codeStartAddress)
        assertEquals(41402, image.startLine)

        assertSendsCapturedPackets(image, "nrf52_micropython_demo_effects.txt")
    }

    @Test
    fun secondMicroPythonProgramMatchesCapturedSession() {
        val image = PartialFlashImage.locate(Fixtures.hex("demo_snake"), FlashLayout.NRF52)!!

        assertTrue(image.isMicroPython)
        assertEquals("0EE8CB7600000000", image.runtimeHash)
        assertEquals(0x6D000L, image.codeStartAddress)

        assertSendsCapturedPackets(image, "nrf52_micropython_demo_snake.txt")
    }

    @Test
    fun microPythonRefusesTheWrongPageSize() {
        // The layout table says 4 KB pages; an nRF51 has 1 KB pages.
        assertNull(PartialFlashImage.locateMicroPython(Fixtures.hex("demo_effects"), FlashLayout.NRF51))
    }

    @Test
    fun makeCodeIsTriedBeforeMicroPython() {
        // A MakeCode hex has no MicroPython header and vice versa.
        assertNotNull(PartialFlashImage.locateMakeCode(Fixtures.hex("demo_matrix")))
        assertNull(PartialFlashImage.locateMicroPython(Fixtures.hex("demo_matrix"), FlashLayout.NRF52))
        assertNull(PartialFlashImage.locateMakeCode(Fixtures.hex("demo_effects")))
    }

    @Test
    fun hexWithoutAProgramHeaderHasNoImage() {
        val hex = IntelHexFile.of(
            listOf(
                ":020000040000FA",
                ":10000000C0070000D1060000D1000000B1060000CA",
                ":00000001FF",
            ),
        )
        assertNull(PartialFlashImage.locate(hex, FlashLayout.NRF52))
        assertNull(PartialFlashImage.locate(hex, FlashLayout.NRF51))
    }

    @Test
    fun batchesCarryTheAddressInTheFirstTwoPacketsAndPadTheLastOne() {
        // Five 16-byte records after the PXT header → 6 payloads = one full
        // batch and a half, padded to two.
        val lines = mutableListOf(":020000040004F6")
        lines += ":107000" + "00" + "708E3B92C615A841C49866C975EE5197" + "00"
        lines += ":107010" + "00" + "0011223344556677" + "8899AABBCCDDEEFF" + "00"
        lines += ":107020" + "00" + "A".repeat(32) + "00"
        lines += ":107030" + "00" + "B".repeat(32) + "00"
        lines += ":107040" + "00" + "C".repeat(32) + "00"
        lines += ":107050" + "00" + "D".repeat(32) + "00"
        lines += ":00000001FF"
        val image = PartialFlashImage.locateMakeCode(IntelHexFile.of(lines))!!

        assertEquals("0011223344556677", image.runtimeHash)   // the 8 bytes right after the header
        assertEquals(0x47000L, image.codeStartAddress)
        assertEquals(6, image.dataLineCount)

        val batches = image.batches()
        assertEquals(2, batches.size)

        val first = batches[0].packets
        assertEquals(listOf(0x7000, 0x0004, 0, 0), first.map { it.offset })
        assertEquals("708E3B92C615A841C49866C975EE5197", first[0].payloadHex)
        assertEquals("A".repeat(32), first[2].payloadHex)
        assertEquals(3, batches[0].linesSent)

        val second = batches[1].packets
        assertEquals(listOf(0x7040, 0x0004, 0, 0), second.map { it.offset })
        assertEquals("D".repeat(32), second[1].payloadHex)
        assertEquals("F".repeat(32), second[2].payloadHex)
        assertEquals("F".repeat(32), second[3].payloadHex)
        assertEquals(6, batches[1].linesSent)
    }

    @Test
    fun packetEncodingPutsCommandOffsetAndNumberInFront() {
        val packet = PartialFlashImage.Packet(0x7000, "708E3B92C615A841C49866C975EE5197").encode(0x1A5)
        assertEquals(
            "0170 00A5 708E3B92C615A841C49866C975EE5197".replace(" ", ""),
            packet.toHex(),
        )
    }

    private fun assertSendsCapturedPackets(image: PartialFlashImage, capture: String) {
        val expected = Fixtures.capturedPackets(capture)
        var packetNum = 0
        val actual = image.batches().flatMap { batch -> batch.packets.map { it.encode(packetNum++).toHex() } }
        assertEquals("packet count", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("packet #$i", expected[i], actual[i])
        }
        assertEquals(0, actual.size % 4)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
