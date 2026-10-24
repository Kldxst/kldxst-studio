package com.kldxst.studio

import com.kldxst.studio.bt.FrameCodec
import com.kldxst.studio.bt.ProtocolProfile
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameCodecTest {

    @Test
    fun `默认帧头往返`() {
        val profile = ProtocolProfile()
        val payload = "hello".toByteArray()
        val wire = FrameCodec.encode(profile, 0x10, payload)
        assertEquals("a5100500", FrameCodec.hex(wire.copyOfRange(0, 4)))
        val (frames, rest) = FrameCodec.decode(profile, wire)
        assertEquals(1, frames.size)
        assertEquals(0x10, frames[0].type)
        assertArrayEquals(payload, frames[0].payload)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `大端长度和自定义偏移`() {
        val profile = ProtocolProfile(
            magic = 0x5A,
            typeOffset = 3,
            lengthOffset = 4,
            lengthBytes = 2,
            littleEndianLength = false,
            headerBytes = 6
        )
        val payload = ByteArray(3) { it.toByte() }
        val wire = FrameCodec.encode(profile, 0x7E, payload)
        assertEquals("5a00007e0003", FrameCodec.hex(wire.copyOfRange(0, 6)))
        val (frames, rest) = FrameCodec.decode(profile, wire)
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0].payload)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `半包保留到下一次解码`() {
        val profile = ProtocolProfile()
        val wire = FrameCodec.encode(profile, 2, ByteArray(20) { it.toByte() })
        val first = FrameCodec.decode(profile, wire.copyOfRange(0, 8))
        assertTrue(first.first.isEmpty())
        assertArrayEquals(wire.copyOfRange(0, 8), first.second)

        val second = FrameCodec.decode(profile, first.second + wire.copyOfRange(8, wire.size))
        assertEquals(1, second.first.size)
        assertTrue(second.second.isEmpty())
    }

    @Test
    fun `可以跨过前导噪声并保留尾部半包`() {
        val profile = ProtocolProfile()
        val wire = FrameCodec.encode(profile, 9, byteArrayOf(1, 2, 3))
        val partial = FrameCodec.encode(profile, 10, ByteArray(4)).copyOfRange(0, 5)
        val (frames, rest) = FrameCodec.decode(profile, byteArrayOf(0, 1, 2) + wire + partial)
        assertEquals(1, frames.size)
        assertEquals(9, frames[0].type)
        assertArrayEquals(partial, rest)
    }

    @Test
    fun `旧版 authMode 迁移到新的 AES 命名`() {
        val p = ProtocolProfile.fromJson(
            JSONObject()
                .put("authMode", "aes-ecb-16")
                .put("ops", JSONObject().put("notify", 16))
        )
        assertEquals("aes-ecb-128", p.authMode)
        assertEquals(16, p.ops["notify"] ?: -1)
        assertEquals(4, p.headerBytes)
    }

    @Test
    fun `字段重叠的配置被拒绝`() {
        try {
            ProtocolProfile(typeOffset = 2, lengthOffset = 2)
            fail("应当拒绝重叠的 type/length 偏移")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("重叠"))
        }
    }

    @Test
    fun `超过 maxFrame 的负载被拒绝`() {
        val profile = ProtocolProfile(maxFrame = 64)
        try {
            FrameCodec.encode(profile, 1, ByteArray(65))
            fail("应当拒绝超长负载")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("超过"))
        }
    }

    @Test
    fun `十六进制解析忽略空白并拒绝奇数长度`() {
        assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C), FrameCodec.unhex("0a 0b\n0c"))
        assertNull(FrameCodec.unhex("abc"))
        assertNull(FrameCodec.unhex("zz"))
    }
}
