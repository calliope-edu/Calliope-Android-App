package cc.calliope.mini.core.hex

/**
 * Where things live in flash on each Calliope mini silicon class — the
 * facts a hex file has to be read against.
 *
 * [applicationRange] is the span the DFU image is cut from (SoftDevice and
 * bootloader excluded); [universalBlockType] is the Microsoft Universal Hex
 * block type whose data belongs to this board (`0x9900` micro:bit v1 class,
 * `0x9903` v2 class); [pageSize] is the flash page the partial-flashing
 * layout table counts in.
 */
enum class FlashLayout(
    val pageSize: Int,
    val applicationRange: LongRange,
    val universalBlockType: Int,
) {
    /** mini 1.x / 2.x — nRF51822, 256 KB flash in 1 KB pages. */
    NRF51(pageSize = 0x400, applicationRange = 0x18000L..0x3BFFFL, universalBlockType = 0x9900),

    /** mini 3 — nRF52833, 512 KB flash in 4 KB pages. */
    NRF52(pageSize = 0x1000, applicationRange = 0x1C000L..0x72FFFL, universalBlockType = 0x9903),
}
