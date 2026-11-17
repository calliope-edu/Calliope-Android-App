package cc.calliope.mini.core.hex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelHexRecordTest {

    @Test
    fun parsesDataRecordFields() {
        val record = IntelHexRecord.parse(":10000000C0070000D1060000D1000000B1060000CA")!!

        assertEquals(16, record.byteCount)
        assertEquals(0x0000, record.address)
        assertEquals(IntelHexRecord.TYPE_DATA, record.type)
        assertEquals("C0070000D1060000D1000000B1060000", record.dataHex)
        assertEquals(0xCA, record.checksum)
        assertTrue(record.isData)
        assertTrue(record.checksumValid)
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 0x07, 0, 0, 0xD1.toByte(), 0x06, 0, 0, 0xD1.toByte(), 0, 0, 0, 0xB1.toByte(), 0x06, 0, 0),
            record.data(),
        )
    }

    @Test
    fun parsesExtendedLinearAddressAndUniversalHexRecords() {
        val ela = IntelHexRecord.parse(":020000040000FA")!!
        assertEquals(IntelHexRecord.TYPE_EXTENDED_LINEAR_ADDRESS, ela.type)
        assertEquals("0000", ela.dataHex)
        assertTrue(ela.checksumValid)

        val blockStart = IntelHexRecord.parse(":0400000A9900C0DEBB")!!
        assertEquals(IntelHexRecord.TYPE_UHEX_BLOCK_START, blockStart.type)
        assertEquals("9900C0DE", blockStart.dataHex)
        assertFalse(blockStart.isData)
        assertTrue(blockStart.checksumValid)

        val custom = IntelHexRecord.parse(":104AF00DC421002040200020FE307F598F71C46DED")!!
        assertEquals(IntelHexRecord.TYPE_UHEX_CUSTOM_DATA, custom.type)
        assertTrue(custom.isData)
        assertTrue(custom.checksumValid)
    }

    @Test
    fun flagsBadChecksumWithoutRejectingTheRecord() {
        val record = IntelHexRecord.parse(":020000040000FB")!!
        assertFalse(record.checksumValid)
    }

    @Test
    fun rejectsLinesThatAreNotRecords() {
        assertNull(IntelHexRecord.parse(""))
        assertNull(IntelHexRecord.parse("020000040000FA"))
        assertNull(IntelHexRecord.parse(":0200000400"))
        assertNull(IntelHexRecord.parse(":10000000C0070000D1060000D1000000B106"))
        assertNull(IntelHexRecord.parse(":02000004ZZ00FA"))
    }

    @Test
    fun acceptsLowerCaseHexAndNormalisesData() {
        val record = IntelHexRecord.parse(":020000040000fa")!!
        assertEquals("0000", record.dataHex)
        assertTrue(record.checksumValid)
    }
}
