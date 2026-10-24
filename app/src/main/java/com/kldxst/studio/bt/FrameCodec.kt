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
    /** 头部中类型字节的位置。magic 固定在 0。 */
    val typeOffset: Int = 1,
    /** 头部中长度字段的位置。默认紧随类型字节。 */
    val lengthOffset: Int = 2,
    val lengthBytes: Int = 2,
    val littleEndianLength: Boolean = true,
    val headerBytes: Int = 4,
    val maxFrame: Int = 8192,
    /** authkey 握手：hmac-sha256 / aes-ecb-128 / aes-ecb-256 / none */
    val authMode: String = "hmac-sha256",
    val authType: Int = 0x01,
    /** 手机 nonce 的字节数，MiWear 的 auth 帧由真机校准 */
    val authNonceBytes: Int = 16,
    /** 业务消息号：操作名 → 帧类型字节 */
    val ops: Map<String, Int> = emptyMap()
) {
    init {
        require(magic in 0..255) { "magic 必须在 0..255" }
        require(typeOffset in 1..15) { "typeOffset 必须在 1..15" }
        require(lengthOffset in 1..15) { "lengthOffset 必须在 1..15" }
        require(lengthBytes in 1..4) { "lengthBytes 必须在 1..4" }
        require(!(lengthOffset < typeOffset + 1 && lengthOffset + lengthBytes > typeOffset)) {
            "类型字段与长度字段不能重叠"
        }
        require(headerBytes >= maxOf(typeOffset + 1, lengthOffset + lengthBytes)) {
            "headerBytes 必须容下类型与长度字段"
        }
        require(headerBytes in 2..64) { "headerBytes 必须在 2..64" }
        require(maxFrame in 64..65535) { "maxFrame 必须在 64..65535" }
        require(authMode in setOf("hmac-sha256", "aes-ecb-128", "aes-ecb-256", "none")) {
            "不支持的 authMode：$authMode"
        }
        require(authType in 0..255) { "authType 必须在 0..255" }
        require(authNonceBytes in 8..32) { "authNonceBytes 必须在 8..32" }
        require(ops.all { (k, v) -> k.isNotBlank() && v in 1..255 }) { "命令消息号必须是 1..255" }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("magic", magic)
        put("typeOffset", typeOffset)
        put("lengthOffset", lengthOffset)
        put("lengthBytes", lengthBytes)
        put("littleEndianLength", littleEndianLength)
        put("headerBytes", headerBytes)
        put("maxFrame", maxFrame)
        put("authMode", authMode)
        put("authType", authType)
        put("authNonceBytes", authNonceBytes)
        put("ops", JSONObject.wrap(ops))
    }

    companion object {
        val AUTH_MODES = setOf("hmac-sha256", "aes-ecb-128", "aes-ecb-256", "none")

        fun fromJson(o: JSONObject?): ProtocolProfile {
            if (o == null) return ProtocolProfile()
            val ops = LinkedHashMap<String, Int>()
            o.optJSONObject("ops")?.let { m ->
                for (k in m.keys()) {
                    val v = m.optInt(k, 0)
                    if (k.isNotBlank() && v in 1..255) ops[k] = v
                }
            }
            val typeOffset = o.optInt("typeOffset", 1).coerceIn(1, 15)
            val defaultLengthOffset = if (typeOffset == 1) 2 else typeOffset + 1
            val lengthOffset = o.optInt("lengthOffset", defaultLengthOffset).coerceIn(1, 15)
            val lengthBytes = o.optInt("lengthBytes", 2).coerceIn(1, 4)
            val requiredHeader = maxOf(typeOffset + 1, lengthOffset + lengthBytes)
            val authMode = when (val raw = o.optString("authMode", "hmac-sha256")) {
                "aes-ecb-16" -> "aes-ecb-128"
                "aes-ecb-32" -> "aes-ecb-256"
                else -> raw
            }
            return ProtocolProfile(
                magic = o.optInt("magic", 0xA5).coerceIn(0, 255),
                typeOffset = typeOffset,
                lengthOffset = lengthOffset,
                lengthBytes = lengthBytes,
                littleEndianLength = o.optBoolean("littleEndianLength", true),
                headerBytes = o.optInt("headerBytes", maxOf(4, requiredHeader)).coerceIn(requiredHeader, 64),
                maxFrame = o.optInt("maxFrame", 8192).coerceIn(64, 65535),
                authMode = authMode,
                authType = o.optInt("authType", 1).coerceIn(0, 255),
                authNonceBytes = o.optInt("authNonceBytes", 16).coerceIn(8, 32),
                ops = ops
            )
        }
    }
}

object FrameCodec {

    /** 组一帧：头部按 profile 布局，payload 紧跟在 headerBytes 之后 */
    fun encode(profile: ProtocolProfile, type: Int, payload: ByteArray): ByteArray {
        require(type in 0..255) { "帧类型必须在 0..255" }
        require(payload.size <= profile.maxFrame) { "帧负载超过 ${profile.maxFrame} 字节" }
        val header = ByteArray(profile.headerBytes)
        header[0] = profile.magic.toByte()
        header[profile.typeOffset] = type.toByte()
        val len = payload.size
        if (profile.littleEndianLength) {
            for (i in 0 until profile.lengthBytes) {
                header[profile.lengthOffset + i] = ((len ushr (8 * i)) and 0xFF).toByte()
            }
        } else {
            for (i in 0 until profile.lengthBytes) {
                header[profile.lengthOffset + i] = ((len ushr (8 * (profile.lengthBytes - 1 - i))) and 0xFF).toByte()
            }
        }
        val out = ByteArrayOutputStream(profile.headerBytes + payload.size)
        out.write(header)
        out.write(payload)
        return out.toByteArray()
    }

    /** 从缓冲区里切出完整帧；返回 (帧列表, 剩余字节) */
    fun decode(profile: ProtocolProfile, buffer: ByteArray): Pair<List<Frame>, ByteArray> {
        val frames = ArrayList<Frame>()
        var offset = 0
        val minHeader = maxOf(profile.headerBytes, profile.typeOffset + 1, profile.lengthOffset + profile.lengthBytes)
        while (buffer.size - offset >= minHeader) {
            if ((buffer[offset].toInt() and 0xFF) != profile.magic) {
                offset++
                continue
            }
            val type = buffer[offset + profile.typeOffset].toInt() and 0xFF
            var len = 0
            for (i in 0 until profile.lengthBytes) {
                val b = buffer[offset + profile.lengthOffset + i].toInt() and 0xFF
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
