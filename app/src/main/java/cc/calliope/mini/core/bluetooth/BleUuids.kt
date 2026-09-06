package cc.calliope.mini.core.bluetooth

import java.util.UUID

/**
 * Every GATT UUID the app talks to, in one place. The partial-flashing
 * service keeps its own protocol UUIDs (phase 7 territory).
 */
object BleUuids {
    /** Client Characteristic Configuration descriptor (notifications on/off). */
    @JvmField val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Nordic UART as exposed by the micro:bit / Calliope runtime -------
    // Note the micro:bit naming: TX is what the BOARD transmits (notify to
    // us), RX is what the board receives (we write). Verified on hardware.
    @JvmField val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    /** Board → phone, notify. */
    @JvmField val UART_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    /** Phone → board, write. */
    @JvmField val UART_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    // ---- DFU ---------------------------------------------------------------
    /** micro:bit DFU control service (nRF51 boards, legacy DFU trigger). */
    @JvmField val DFU_CONTROL_SERVICE: UUID = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8")
    /** Writing 0x01 here reboots an nRF51 board into its DFU bootloader. */
    @JvmField val DFU_CONTROL_CHARACTERISTIC: UUID = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8")
    /** Nordic Secure DFU service (nRF52 boards). */
    @JvmField val SECURE_DFU_SERVICE: UUID = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")

    // ---- MbitMore (pxt-blocks-runtime) ------------------------------------
    @JvmField val MBIT_MORE_SERVICE: UUID = UUID.fromString("0b50f3e4-607f-4151-9091-7d008d6ffc5c")
    @JvmField val MBIT_MORE_STATE: UUID = UUID.fromString("0b500101-607f-4151-9091-7d008d6ffc5c")
}
