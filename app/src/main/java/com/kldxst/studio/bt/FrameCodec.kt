package com.kldxst.studio.bt

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MiWear 帧编解码。
 *
 * 帧格式与业务消息号都**由服务端下发**（`/api/protocol`），因为这套协议没有公开文档：
 * AzkiDeck 把它编译进了 2.2MB 的 `libazvfnative.so`，AstroBox 的 core 是闭源的。
 * 参数放在服务端意味着：真机上校准一次，之后所有人拿到正确的帧，不用重装应用。
 *
 * 默认值是按常见实现推的骨架（magic 0xA5、小端长度、类型字节），
 * 未经真机确认，所以界面上把未校准的写操作标成「待校准」。
 */
data class ProtocolProfile(
    val magic: Int = 0xA5,
    val typeOffset: Int = 1,
    val lengthBytes: Int = 2,
    val littleEndianLength: Boolean = true,
    val headerBytes: Int = 4,
    val maxFrame: Int = 8192,
    /** authkey 握手：aes-ecb-16 / aes-ecb-32 / none */
    val authMode: String = "aes-ecb-16",
    val authType: Int = 0x01,
    /** 业务消息号：操作名 → 帧类型字节 */
    val ops: Map<String, Int> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("magic", magic)
        put("typeOffset", typeOffset)
        put("lengthBytes", lengthBytes)
        put("littleEndianLength", littleEndianLength)
        put("headerBytes", headerBytes)
        put("maxFrame", maxFrame)
        put("authMode", authMode)
        put("authType", authType)
        put("ops", JSONObject.wrap(ops))
    }

    companion object {
        fun fromJson(o: JSONObject?): ProtocolProfile {
            if (o == null) return ProtocolProfile()
            val ops = LinkedHashMap<String, Int>()
            o.optJSONObject("ops")?.let { m ->
                for (k in m.keys()) ops[k] = m.optInt(k, 0)
            }
            return ProtocolProfile(
                magic = o.optInt("magic", 0xA5) and 0xFF,
                typeOffset = o.optInt("typeOffset", 1).coerceIn(0, 3),
                lengthBytes = o.optInt("lengthBytes", 2).coerceIn(1, 4),
                littleEndianLength = o.optBoolean("littleEndianLength", true),
                headerBytes = o.optInt("headerBytes", 4).coerceIn(2, 8),
                maxFrame = o.optInt("maxFrame", 8192).coerceIn(64, 65536),
                authMode = o.optString("authMode", "aes-ecb-16"),
                authType = o.optInt("authType", 1) and 0xFF,
                ops = ops
            )
        }
    }
}

object FrameCodec {

    /** 组一帧：magic + type + 长度 + payload */
    fun encode(profile: ProtocolProfile, type: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + profile.headerBytes)
        out.write(profile.magic)
        out.write(type and 0xFF)
        val len = payload.size
        if (profile.littleEndianLength) {
            for (i in 0 until profile.lengthBytes) out.write((len ushr (8 * i)) and 0xFF)
        } else {
            for (i in profile.lengthBytes - 1 downTo 0) out.write((len ushr (8 * i)) and 0xFF)
        }
        // 头部不足 headerBytes 时补零
        while (out.size() < profile.headerBytes) out.write(0)
        out.write(payload)
        return out.toByteArray()
    }

    /** 从缓冲区里切出完整帧；返回 (帧列表, 剩余字节) */
    fun decode(profile: ProtocolProfile, buffer: ByteArray): Pair<List<Frame>, ByteArray> {
        val frames = ArrayList<Frame>()
        var offset = 0
        while (buffer.size - offset >= profile.headerBytes) {
            if ((buffer[offset].toInt() and 0xFF) != profile.magic) {
                offset++
                continue
            }
            val type = buffer[offset + profile.typeOffset].toInt() and 0xFF
            var len = 0
            for (i in 0 until profile.lengthBytes) {
                val b = buffer[offset + 2 + i].toInt() and 0xFF
                len = if (profile.littleEndianLength) len or (b shl (8 * i)) else (len shl 8) or b
            }
            if (len < 0 || len > profile.maxFrame) {
                offset++
                continue
            }
            val total = profile.headerBytes + len
            if (buffer.size - offset < total) break
            frames.add(Frame(type, buffer.copyOfRange(offset + profile.headerBytes, offset + total)))
            offset += total
        }
        return frames to buffer.copyOfRange(offset, buffer.size)
    }

    fun hex(bytes: ByteArray): String {
        val t = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(t[v ushr 4]).append(t[v and 0x0F])
        }
        return sb.toString()
    }

    fun unhex(s: String): ByteArray? {
        val t = s.replace(" ", "").replace("\n", "")
        if (t.length % 2 != 0 || !t.matches(Regex("[0-9a-fA-F]*"))) return null
        return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 小端 32 位，MiWear 里时间戳/容量都是这个形状 */
    fun le32(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

    fun u8(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte())

    fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
}

data class Frame(val type: Int, val payload: ByteArray)
