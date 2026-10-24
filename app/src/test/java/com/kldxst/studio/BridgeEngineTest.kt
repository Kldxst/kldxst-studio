package com.kldxst.studio

import com.kldxst.studio.bridge.Base64Codec
import com.kldxst.studio.bridge.BridgeEngine
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bridge.FetchRequest
import com.kldxst.studio.bridge.FetchResult
import com.kldxst.studio.bridge.HttpFetcher
import com.kldxst.studio.bridge.SsrfGuard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议用例：把真实帧喂进引擎，断言输出符合 FetchBridge/PROTOCOL.md。
 *
 * 这些用例跑在 JVM 上，不需要手环 —— 也就是说「协议实现对不对」在 CI 就能守住，
 * 不会出现「装到手机上才发现握手回错」这种情况。
 */
class BridgeEngineTest {

    private val fake = object : HttpFetcher {
        override fun fetch(req: FetchRequest): FetchResult {
            if (req.url.contains("/big")) {
                return FetchResult(
                    200, "OK",
                    mapOf("content-type" to "application/octet-stream"),
                    ByteArray(9000) { (it % 251).toByte() }
                )
            }
            if (req.url.contains("/json")) {
                return FetchResult(
                    200, "OK", mapOf("content-type" to "application/json"),
                    "{\"ok\":true,\"name\":\"幽径\"}".toByteArray()
                )
            }
            return FetchResult(404, "Not Found", emptyMap(), "nope".toByteArray())
        }
    }

    private fun engine(policy: BridgePolicy = BridgePolicy()) =
        BridgeEngine(fake, { policy }, {})

    private fun frames(out: MutableList<String>): List<JSONObject> =
        out.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }

    @Test
    fun `握手回 count+1 并带 caps`() {
        val out = ArrayList<String>()
        engine().onFrame("a", "p", """{"tag":"__hs__","count":0,"caps":{"version":1}}""") { out.add(it) }
        assertEquals(1, out.size)
        val o = JSONObject(out[0])
        assertEquals("__hs__", o.getString("tag"))
        assertEquals(1, o.getInt("count"))
        assertNotNull(o.optJSONObject("caps"))
    }

    @Test
    fun `count 到 2 不再回包`() {
        val out = ArrayList<String>()
        engine().onFrame("a", "p", """{"tag":"__hs__","count":2}""") { out.add(it) }
        assertEquals(0, out.size)
    }

    @Test
    fun `v1 单消息返回文本体`() {
        val out = ArrayList<String>()
        val e = engine()
        e.onFrame("a", "p", """{"tag":"__hs__","count":0,"caps":{"version":1,"encodings":["text","base64"]}}""") { out.add(it) }
        e.onFrame("a", "p", """{"tag":"fetch","id":"1","url":"https://x.test/json","options":{"method":"GET"}}""") { out.add(it) }
        val resp = JSONObject(out.last()).getJSONObject("resp")
        assertTrue(resp.getBoolean("ok"))
        assertEquals(200, resp.getInt("status"))
        assertTrue(resp.getString("body").contains("幽径"))
        assertEquals("text", resp.getString("bodyEncoding"))
    }

    @Test
    fun `未声明 text 时走 base64`() {
        val out = ArrayList<String>()
        val e = engine()
        e.onFrame("a", "p", """{"tag":"__hs__","count":0,"caps":{"version":1,"encodings":["base64"]}}""") { out.add(it) }
        e.onFrame("a", "p", """{"tag":"fetch","id":"2","url":"https://x.test/json","options":{}}""") { out.add(it) }
        val resp = JSONObject(out.last()).getJSONObject("resp")
        assertEquals("base64", resp.getString("bodyEncoding"))
        val decoded = String(Base64Codec.decode(resp.getString("body")))
        assertTrue(decoded.contains("幽径"))
    }

    @Test
    fun `分片 + 累计 ACK 能把大响应收齐`() {
        val out = ArrayList<String>()
        val e = engine()
        e.onFrame(
            "a", "p",
            """{"tag":"__hs__","count":0,"caps":{"version":3,"chunk":true,"ack":true,"maxChunkSize":1024,"encodings":["hex"],"ackWindow":2}}"""
        ) { out.add(it) }
        e.onFrame("a", "p", """{"tag":"fetch","id":"3","url":"https://x.test/big","options":{}}""") { out.add(it) }

        val header = frames(out).first { it.optString("tag") == "fetch" }.getJSONObject("resp")
        assertTrue(header.getBoolean("chunked"))
        val total = header.getInt("chunkCount")
        assertTrue(total > 1)

        var guard = 0
        while (guard++ < 100) {
            val got = frames(out).filter { it.optString("tag") == "fetch-chunk" }
            if (got.size >= total) break
            val seqs = got.map { it.getInt("seq") }.toSet()
            var contiguous = 0
            while (seqs.contains(contiguous)) contiguous++
            e.onFrame("a", "p", """{"tag":"fetch-ack","id":"3","ack":$contiguous}""") { out.add(it) }
        }
        val seqs = frames(out).filter { it.optString("tag") == "fetch-chunk" }.map { it.getInt("seq") }.toSet()
        assertEquals(total, seqs.size)
    }

    @Test
    fun `明文 http 与内网地址一律拒绝`() {
        val e = engine()
        val out = ArrayList<String>()
        e.onFrame("a", "p", """{"tag":"fetch","id":"4","url":"http://127.0.0.1/admin","options":{}}""") { out.add(it) }
        assertFalse(JSONObject(out.last()).getJSONObject("resp").getBoolean("ok"))

        out.clear()
        e.onFrame("a", "p", """{"tag":"fetch","id":"5","url":"https://192.168.1.1/","options":{}}""") { out.add(it) }
        assertFalse(JSONObject(out.last()).getJSONObject("resp").getBoolean("ok"))

        out.clear()
        e.onFrame("a", "p", """{"tag":"fetch","id":"6","url":"https://169.254.169.254/latest/meta-data","options":{}}""") { out.add(it) }
        assertFalse(JSONObject(out.last()).getJSONObject("resp").getBoolean("ok"))
    }

    @Test
    fun `不在允许清单的包名被拒绝`() {
        val policy = BridgePolicy(allowPackages = setOf("com.kldxst.allowed"))
        val out = ArrayList<String>()
        engine(policy).onFrame("a", "com.evil.app", """{"tag":"fetch","id":"7","url":"https://x.test/json"}""") { out.add(it) }
        val resp = JSONObject(out.last()).getJSONObject("resp")
        assertFalse(resp.getBoolean("ok"))
        assertTrue(resp.getString("error").contains("未被授权"))
    }

    @Test
    fun `SSRF 守卫拦下回环与链路本地`() {
        assertNotNull(SsrfGuard.check("https://127.0.0.1/", BridgePolicy()))
        assertNotNull(SsrfGuard.check("https://[::1]/", BridgePolicy()))
        assertNotNull(SsrfGuard.check("https://[fc00::1]/", BridgePolicy()))
        assertNotNull(SsrfGuard.check("https://169.254.169.254/", BridgePolicy()))
        assertNotNull(SsrfGuard.check("https://100.64.0.1/", BridgePolicy()))
        assertNotNull(SsrfGuard.check("http://example.com/", BridgePolicy(allowHttp = false)))
        // 带 userinfo 的伪装地址必须拒
        assertNotNull(SsrfGuard.check("https://example.com@127.0.0.1/", BridgePolicy()))
        // 白名单外的直连 IP 也不能绕过
        assertNotNull(SsrfGuard.check("https://8.8.8.8/", BridgePolicy(allowHosts = setOf("example.com"))))
    }

    @Test
    fun `策略局部更新不会丢掉未提交字段`() {
        val old = BridgePolicy(
            allowPackages = setOf("com.kldxst.demo"),
            allowHosts = setOf("old.example"),
            chunkSize = 1024,
            ackWindow = 2
        )
        val next = BridgePolicy.fromJson(JSONObject().put("allowHosts", org.json.JSONArray().put("new.example")), old)
        assertEquals(setOf("com.kldxst.demo"), next.allowPackages)
        assertEquals(setOf("new.example"), next.allowHosts)
        assertEquals(1024, next.chunkSize)
        assertEquals(2, next.ackWindow)
    }

    @Test
    fun `base64 编解码往返`() {
        val data = ByteArray(1000) { (it * 7 % 256).toByte() }
        val text = Base64Codec.encode(data)
        assertTrue(Base64Codec.decode(text).contentEquals(data))
        assertEquals("", Base64Codec.encode(ByteArray(0)))
    }

    @Test
    fun `自检四步全过`() {
        val r = engine().selftest(fake)
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals(0, r.getInt("fail"))
    }
}
