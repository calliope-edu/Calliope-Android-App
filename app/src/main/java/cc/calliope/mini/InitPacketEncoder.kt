package cc.calliope.mini

import cc.calliope.mini.utils.Constants.MINI_V2
import cc.calliope.mini.utils.Constants.MINI_V3
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface InitPacketEncoder {
    fun encode(firmware: ByteArray): ByteArray
}

class InitPacketV2(
    private val deviceType: UShort,
    private val deviceRevision: UShort,
    private val applicationVersion: UInt,
    private val softdevicesCount: UShort,
    private val softdevice: UShort
) : InitPacketEncoder {

    constructor() : this(
        deviceType = 0xFFFFu,
        deviceRevision = 0xFFFFu,
        applicationVersion = 0xFFFFFFFFu,
        softdevicesCount = 0x0001u,
        softdevice = 0x0064u
    )

    override fun encode(firmware: ByteArray): ByteArray {
        val checksum = crc16(firmware)
        val initPacket = mutableListOf<Byte>()

        initPacket.addAll(deviceType.toLittleEndianByteArray().toList())
        initPacket.addAll(deviceRevision.toLittleEndianByteArray().toList())
        initPacket.addAll(applicationVersion.toLittleEndianByteArray().toList())
        initPacket.addAll(softdevicesCount.toLittleEndianByteArray().toList())
        initPacket.addAll(softdevice.toLittleEndianByteArray().toList())
        initPacket.addAll(checksum.toLittleEndianByteArray().toList())

        return initPacket.toByteArray()
    }

    private fun crc16(data: ByteArray): UShort {
        var crc = 0xFFFFu
        for (b in data) {
            crc = ((crc shr 8) or (crc shl 8)) and 0xFFFFu
            crc = crc xor (b.toUInt() and 0x00FFu)
            crc = crc xor ((crc and 0x00FFu) shr 4)
            crc = crc xor ((crc shl 8) shl 4) and 0xFFFFu
            crc = crc xor (((crc and 0x00FFu) shl 4) shl 1) and 0xFFFFu
        }
        return crc.toUShort()
    }
}

class InitPacketV3(
    private val appName: String,
    private val initPacketVersion: Int,
    private val hashSize: Int,
    private val hashBytes: ByteArray
) : InitPacketEncoder {

    constructor() : this(
        appName = "microbit_app",
        initPacketVersion = 1,
        hashSize = 0,
        hashBytes = ByteArray(32) { 0.toByte() }
    )

    override fun encode(firmware: ByteArray): ByteArray {
        val appSize = firmware.size
        val initPacket = mutableListOf<Byte>()

        initPacket.addAll(appName.toByteArray(Charsets.UTF_8).toList())
        initPacket.addAll(initPacketVersion.toLittleEndianByteArray().toList())
        initPacket.addAll(appSize.toLittleEndianByteArray().toList())
        initPacket.addAll(hashSize.toBigEndianByteArray().toList())
        initPacket.addAll(hashBytes.toList())

        return initPacket.toByteArray()
    }
}

class InitPacket(version: Int) : InitPacketEncoder {
    private val encoder: InitPacketEncoder = when (version) {
        MINI_V2 -> InitPacketV2()
        MINI_V3 -> InitPacketV3()
        else -> throw IllegalArgumentException("Unsupported version: $version")
    }

    override fun encode(firmware: ByteArray): ByteArray = encoder.encode(firmware)
}

// Function for Little/Big Endian
fun Int.toLittleEndianByteArray(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()

fun UInt.toLittleEndianByteArray(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(this.toInt()).array()

fun UShort.toLittleEndianByteArray(): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(this.toShort()).array()

fun Int.toBigEndianByteArray(): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(this).array()