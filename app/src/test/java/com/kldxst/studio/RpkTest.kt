package com.kldxst.studio

import com.kldxst.studio.install.Rpk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RpkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(name: String, manifest: String, extra: Map<String, String> = emptyMap()): File {
        val file = tmp.newFile(name)
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(manifest.toByteArray())
            out.closeEntry()
            for ((path, text) in extra) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `读取 manifest 并计算哈希`() {
        val file = zip(
            "demo.rpk",
            """{"package":"com.kldxst.demo","name":"演示","versionName":"1.2.3","versionCode":12,
               "deviceTypeList":["band"],"features":[{"name":"fetch"}]}"""
        )
        val info = Rpk.inspect(file)
        assertNull(info.error)
        assertEquals("quickapp", info.kind)
        assertEquals("com.kldxst.demo", info.packageName)
        assertEquals("1.2.3", info.versionName)
        assertEquals(12, info.versionCode)
        assertEquals(listOf("band"), info.deviceTypes)
        assertEquals(listOf("fetch"), info.features)
        assertEquals(64, info.sha256.length)
        assertTrue(info.size > 0)
    }

    @Test
    fun `路径穿越条目被拒绝`() {
        val file = zip("evil.rpk", """{"package":"x"}""", mapOf("../escape.txt" to "x"))
        val info = Rpk.inspect(file)
        assertNotNull(info.error)
        assertTrue(info.error!!.contains("非法路径"))
    }

    @Test
    fun `固件裸二进制只做哈希体检`() {
        val file = tmp.newFile("firmware.bin")
        file.writeBytes(byteArrayOf(0x12, 0x34, 0x56, 0x78))
        val info = Rpk.inspect(file)
        assertEquals("firmware", info.kind)
        assertNull(info.error)
        assertEquals(4L, info.size)
        assertEquals(64, info.sha256.length)
    }

    @Test
    fun `缺少 manifest 被拒绝`() {
        val file = tmp.newFile("empty.rpk")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("index.js"))
            out.write("x".toByteArray())
            out.closeEntry()
        }
        val info = Rpk.inspect(file)
        assertNotNull(info.error)
        assertTrue(info.error!!.contains("manifest"))
    }
}
