package cc.calliope.mini.core.hex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HexVersionProbeTest {

    @Test
    fun universalHexIsEvidenceByItsBlockRecords() {
        assertTrue(HexVersionProbe.containsV3Evidence(Fixtures.hexPath("demo_effects")))
    }

    @Test
    fun plainMini3HexIsEvidenceByItsAddresses() {
        assertTrue(HexVersionProbe.containsV3Evidence(Fixtures.hexPath("demo_matrix")))
    }

    @Test
    fun mini12HexIsNoEvidence() {
        // nRF51 program: all flash below 0x40000, plus a UICR record at
        // 0x10001014 that must not count.
        assertFalse(HexVersionProbe.containsV3Evidence(Fixtures.hexPath("one_time_pairing")))
    }

    @Test
    fun missingFileIsNoEvidence() {
        assertFalse(HexVersionProbe.containsV3Evidence(File("does/not/exist.hex").path))
    }
}
