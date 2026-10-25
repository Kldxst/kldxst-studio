package com.kldxst.studio.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiWearProtocolTest {

    @Test
    fun l1FrameMatchesAstroBoxHeader() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = MiWearFrameCodec.encode(MiWearFrameCodec.TYPE_DATA, false, 7, payload)
        assertEquals(0xA5, frame[0].toInt() and 0xFF)
        assertEquals(0xA5, frame[1].toInt() and 0xFF)
        assertEquals(3, frame[2].toInt() and 0x0F)
        assertEquals(7, frame[3].toInt() and 0xFF)
        assertEquals(3, (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8))
        assertEquals(MiWearFrameCodec.crc16Arc(payload), (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8))
        assertArrayEquals(payload, frame.copyOfRange(8, frame.size))
    }

    @Test
    fun l1DecodeHandlesSplitAndTrailingBytes() {
        val a = MiWearFrameCodec.encode(MiWearFrameCodec.TYPE_ACK, false, 2, ByteArray(0))
        val b = MiWearFrameCodec.encode(MiWearFrameCodec.TYPE_DATA, true, 3, byteArrayOf(9, 8, 7))
        val joined = a + b + byteArrayOf(0x55)
        val first = MiWearFrameCodec.decode(joined.copyOfRange(0, 5))
        assertTrue(first.first.isEmpty())
        assertEquals(5, first.second.size)
        val second = MiWearFrameCodec.decode(joined)
        assertEquals(2, second.first.size)
        assertEquals(3, second.first[1].payload.size)
        assertEquals(1, second.second.size)
    }

    @Test
    fun authKdfMatchesReferenceVector() {
        val authkey = ByteArray(16) { it.toByte() }
        val phone = ByteArray(16) { (it + 16).toByte() }
        val watch = ByteArray(16) { (it + 32).toByte() }
        val k = MiWearCrypto.kdf(authkey, phone, watch)
        assertEquals("d738074e6570abb50d001db70f497a37", hex(k.decKey))
        assertEquals("923e295e02aecb7619a8e1b9f574c988", hex(k.encKey))
        assertEquals("8676d225", hex(k.decNonce))
        assertEquals("23869a15", hex(k.encNonce))
    }

    @Test
    fun aesCtrAndCcmRoundTrip() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val nonce = ByteArray(12) { (it * 3).toByte() }
        val plain = "KdSync 智能穿戴管理终端".toByteArray()
        val ccm = MiWearCrypto.aes128Ccm(key, nonce, ByteArray(0), plain)
        assertTrue(ccm.size > plain.size)
        assertArrayEquals(plain, MiWearCrypto.aes128CcmDecrypt(key, nonce, ByteArray(0), ccm))

        val ctr = MiWearCrypto.aes128Ctr(key, plain)
        assertArrayEquals(plain, MiWearCrypto.aes128Ctr(key, ctr))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
