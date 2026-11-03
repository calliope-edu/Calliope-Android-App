package cc.calliope.mini.core.hex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelHexFileTest {

    private val hex = IntelHexFile.of(
        listOf(
            ":020000040000FA",                              // 0: base 0x00000000
            ":10000000C0070000D1060000D1000000B1060000CA",  // 1: data @ 0x0000
            "not a record",                                 // 2
            ":020000040004F6",                              // 3: base 0x00040000
            ":107000000102030405060708090A0B0C0D0E0F1088",  // 4: data @ 0x47000
            ":04701000AABBCCDD36",                          // 5: data @ 0x47010, 4 bytes
            ":00000001FF",                                  // 6: EOF
        ),
    )

    @Test
    fun keepsEveryLineAndTypesNonRecords() {
        assertEquals(7, hex.size)
        assertEquals(IntelHexFile.NO_RECORD, hex.type(2))
        assertEquals("", hex.dataHex(2))
        assertFalse(hex.isData(2))
        assertEquals(IntelHexRecord.TYPE_END_OF_FILE, hex.type(6))
    }

    @Test
    fun resolvesAbsoluteAddressesFromExtendedLinearRecords() {
        assertEquals(0x0000L, hex.address(1))
        assertEquals(0x47000L, hex.address(4))
        assertEquals(0x47010L, hex.address(5))
    }

    @Test
    fun findsTheRecordHoldingAnAddress() {
        assertEquals(1, hex.indexOfAddress(0x0005))
        assertEquals(4, hex.indexOfAddress(0x47000))
        assertEquals(4, hex.indexOfAddress(0x4700F))
        assertEquals(5, hex.indexOfAddress(0x47010))
        assertEquals(5, hex.indexOfAddress(0x47013))
        assertEquals(-1, hex.indexOfAddress(0x47014))
        assertEquals(-1, hex.indexOfAddress(0x10000))
    }

    @Test
    fun searchesByTextAndRegex() {
        assertEquals(4, hex.indexOf("0102030405"))
        assertEquals(5, hex.indexOf("AABB", from = 5))
        assertEquals(-1, hex.indexOf("AABB", from = 6))
        assertEquals(0, hex.indexOf(Regex("^:02000004")))
        assertEquals(3, hex.indexOf(Regex("^:02000004"), from = 1))
        assertTrue(hex.isData(4))
    }

    @Test
    fun honoursExtendedSegmentAddressRecords() {
        val segmented = IntelHexFile.of(
            listOf(
                ":020000021000EC",                          // segment 0x1000 → base 0x10000
                ":040000001122334452",                      // data @ 0x10000
            ),
        )
        assertEquals(0x10000L, segmented.address(1))
    }

    @Test
    fun streamsRecordsWithAddresses() {
        val records = hex.records().toList()
        assertEquals(6, records.size)
        assertEquals(0x47000L, records[3].first)
        assertEquals(IntelHexRecord.TYPE_DATA, records[3].second.type)
    }
}
