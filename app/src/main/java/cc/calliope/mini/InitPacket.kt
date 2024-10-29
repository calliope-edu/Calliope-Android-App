package cc.calliope.mini

import java.nio.ByteBuffer
import java.nio.ByteOrder

class InitPacket(private val appSize: Int) {

    private val appName: ByteArray = "microbit_app".toByteArray(Charsets.UTF_8)
    private val initPacketVersion: Int = 1
    private val hashSize: Int = 0
    private val hashBytes: ByteArray = ByteArray(32) { 0.toByte() }

    fun encode(): ByteArray {
        val initPacket = mutableListOf<Byte>()

        initPacket.addAll(appName.toList())
        initPacket.addAll(initPacketVersion.toLittleEndianByteArray().toList())
        initPacket.addAll(appSize.toLittleEndianByteArray().toList())
        initPacket.addAll(hashSize.toBigEndianByteArray().toList())
        initPacket.addAll(hashBytes.toList())

        return initPacket.toByteArray()
    }
}

fun Int.toLittleEndianByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(4)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(this)
    return buffer.array()
}

fun Int.toBigEndianByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(4)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putInt(this)
    return buffer.array()
}
