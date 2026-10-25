package com.kldxst.studio.wear

import java.io.ByteArrayOutputStream

/**
 * 真实 MiWear L1/SAR 帧。
 *
 * 头部 8 字节，全部来自 AstroBox-NG 公开源码：
 *   magic(2, A5 A5) | type|frx(1) | seq(1) | length(2, LE) | CRC16-ARC(2, LE)
 * CRC 只覆盖 payload。
 */
data class L1Frame(
    val type: Int,
    val frx: Boolean,
    val seq: Int,
    val payload: ByteArray
)

object MiWearFrameCodec {
    const val TYPE_NAK = 0
    const val TYPE_ACK = 1
    const val TYPE_CMD = 2
    const val TYPE_DATA = 3
    const val MAGIC = 0xA5A5
    const val HEADER_BYTES = 8
    const val MAX_PAYLOAD = 60 * 1024

    fun encode(type: Int, frx: Boolean, seq: Int, payload: ByteArray): ByteArray {
        require(type in 0..3) { "非法 L1 类型：$type" }
        require(payload.size <= MAX_PAYLOAD) { "L1 负载超过 60KB" }
        val out = ByteArrayOutputStream(HEADER_BYTES + payload.size)
        out.write(MAGIC and 0xFF)
        out.write((MAGIC ushr 8) and 0xFF)
        out.write((type and 0x0F) or if (frx) 0x10 else 0)
        out.write(seq and 0xFF)
        out.write(payload.size and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        val crc = crc16Arc(payload)
        out.write(crc and 0xFF)
        out.write((crc ushr 8) and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    fun decode(buffer: ByteArray): Pair<List<L1Frame>, ByteArray> {
        val frames = ArrayList<L1Frame>()
        var offset = 0
        while (buffer.size - offset >= HEADER_BYTES) {
            val magic = (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8)
            if (magic != MAGIC) {
                offset++
                continue
            }
            val type = buffer[offset + 2].toInt() and 0x0F
            val frx = (buffer[offset + 2].toInt() and 0x10) != 0
            val seq = buffer[offset + 3].toInt() and 0xFF
            val length = (buffer[offset + 4].toInt() and 0xFF) or
                ((buffer[offset + 5].toInt() and 0xFF) shl 8)
            val total = HEADER_BYTES + length
            if (length > MAX_PAYLOAD || type !in 0..3) {
                offset++
                continue
            }
            if (buffer.size - offset < total) break
            val payload = buffer.copyOfRange(offset + HEADER_BYTES, offset + total)
            val crc = (buffer[offset + 6].toInt() and 0xFF) or
                ((buffer[offset + 7].toInt() and 0xFF) shl 8)
            if (crc != crc16Arc(payload)) {
                // 坏帧不能把后面一整段都吞掉，按一个字节重新同步。
                offset++
                continue
            }
            frames.add(L1Frame(type, frx, seq, payload))
            offset += total
        }
        return frames to buffer.copyOfRange(offset, buffer.size)
    }

    fun crc16Arc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}

/** L2：channel(1) | opcode(1) | payload */
data class L2Frame(val channel: Int, val opcode: Int, val payload: ByteArray)

object L2Codec {
    const val CHANNEL_PB = 1
    const val CHANNEL_MASS = 2
    const val CHANNEL_MASS_VOICE = 3
    const val CHANNEL_FILE_SENSOR = 4
    const val CHANNEL_FILE_FITNESS = 5
    const val CHANNEL_OTA = 6
    const val CHANNEL_NETWORK = 7
    const val OP_WRITE = 1
    const val OP_WRITE_ENC = 2

    fun encode(channel: Int, opcode: Int, payload: ByteArray): ByteArray =
        byteArrayOf(channel.toByte(), opcode.toByte()) + payload

    fun decode(data: ByteArray): L2Frame {
        require(data.size >= 2) { "L2 包太短" }
        return L2Frame(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data.copyOfRange(2, data.size))
    }
}
