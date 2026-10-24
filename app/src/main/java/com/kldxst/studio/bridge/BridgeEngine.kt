package com.kldxst.studio.bridge

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

/**
 * 网桥引擎：FetchBridge v1–v3 的完整实现（v4 流式路径不声明，按规范回退到分片）。
 *
 * 设计要点
 *  - **不认识 android.***：单测在 JVM 上直接跑真实帧序列（见 BridgeEngineTest）。
 *  - 会话按 (设备地址, 快应用包名) 隔离：多台设备、多个快应用并发请求不会串包。
 *  - 帧格式、字段名、ACK 语义逐条对齐 FetchBridge/PROTOCOL.md；
 *    对端只声明 v1 时（现有全部 Kldxst 作品就是这样）走单消息 base64/text。
 *  - 出口限速、域名策略、响应上限全部来自 BridgePolicy，可被服务端热更新。
 */
class BridgeEngine(
    private val fetcher: HttpFetcher,
    @Volatile private var policyProvider: () -> BridgePolicy = { BridgePolicy() },
    private val log: (String) -> Unit = {}
) {

    private val lock = Any()
    private val sessions = HashMap<String, Session>()
    private var totalRequests = 0L
    private var totalBytes = 0L
    private var lastError: String? = null
    private var lastRequestAt = 0L

    fun updatePolicy(p: BridgePolicy) {
        policyProvider = { p }
    }

    val policy: BridgePolicy get() = policyProvider()

    /* ------------------------------------------------------------------ */
    /*  入口                                                              */
    /* ------------------------------------------------------------------ */

    fun onFrame(addr: String, pkg: String, text: String, reply: (String) -> Unit) {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            log("丢弃非 JSON 帧（${text.length} 字节）")
            return
        }
        val tag = obj.optString("tag")
        when (tag) {
            "__hs__" -> handshake(addr, pkg, obj, reply)
            "fetch" -> fetch(addr, pkg, obj, reply)
            "fetch-ack" -> ack(addr, pkg, obj)
            "fetch-stream-ack", "fetch-stream-cancel" -> {
                // 本端不声明 stream，正常不会收到；收到就明确拒绝，避免对端空等
                log("收到未协商的 $tag，已忽略")
            }
            "" -> log("帧缺少 tag，已忽略")
            else -> log("未知 tag=$tag，按规范忽略")
        }
    }

    /* ------------------------------------------------------------------ */
    /*  握手                                                              */
    /* ------------------------------------------------------------------ */

    private fun handshake(addr: String, pkg: String, obj: JSONObject, reply: (String) -> Unit) {
        val count = obj.optInt("count", 0)
        val hasCaps = obj.has("caps") && !obj.isNull("caps")
        val peer = if (hasCaps) PeerCaps.fromJson(obj.optJSONObject("caps")) else PeerCaps()
        synchronized(lock) {
            val s = session(addr, pkg)
            if (hasCaps) {
                s.peer = peer
                s.negotiated = Negotiated.of(peer, policy)
            }
            s.lastActive = System.currentTimeMillis()
            if (count < 2) {
                val out = JSONObject()
                    .put("tag", "__hs__")
                    .put("count", count + 1)
                    .put("caps", localCaps().toJson())
                reply(out.toString())
            }
        }
        log("握手 $pkg count=$count v=${peer.version} chunk=${peer.chunk} ack=${peer.ack}")
    }

    private fun localCaps(): PeerCaps = PeerCaps(
        version = BridgeCaps.LOCAL_VERSION,
        chunk = BridgeCaps.LOCAL_CHUNK,
        ack = BridgeCaps.LOCAL_ACK,
        stream = BridgeCaps.LOCAL_STREAM,
        maxChunkSize = policy.chunkSize,
        encodings = Negotiated.LOCAL_ENCODINGS,
        compressions = Negotiated.LOCAL_COMPRESSIONS,
        ackWindow = policy.ackWindow
    )

    /* ------------------------------------------------------------------ */
    /*  fetch                                                            */
    /* ------------------------------------------------------------------ */

    private fun fetch(addr: String, pkg: String, obj: JSONObject, reply: (String) -> Unit) {
        val policy = policyProvider()
        val id = obj.optString("id")
        val url = obj.optString("url")
        val options = obj.optJSONObject("options")

        val allow = policy.allowPackages
        if (allow != null && !allow.contains(pkg)) {
            log("拒绝 $pkg：不在允许清单")
            reply(errorResponse(id, "包名 $pkg 未被授权使用网桥"))
            return
        }
        if (url.isBlank()) {
            reply(errorResponse(id, "缺少 url"))
            return
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = session(addr, pkg)
            s.lastActive = now
            s.stamps.addLast(now)
            while (s.stamps.isNotEmpty() && now - s.stamps.first() > 60_000) s.stamps.removeFirst()
            if (s.stamps.size > policy.maxRequestsPerMinute) {
                log("限流：$pkg 每分钟超过 ${policy.maxRequestsPerMinute} 次")
                reply(errorResponse(id, "请求过于频繁，请稍后重试"))
                return
            }
        }

        val headers = LinkedHashMap<String, String>()
        options?.optJSONObject("headers")?.let { h ->
            for (k in h.keys()) {
                val v = h.opt(k)
                if (v != null && v != JSONObject.NULL) headers[k] = v.toString()
            }
        }
        val bodyText = options?.optString("body")?.takeIf { it.isNotEmpty() }
        val req = FetchRequest(
            url = url,
            method = options?.optString("method")?.takeIf { it.isNotBlank() } ?: "GET",
            headers = headers,
            body = bodyText?.toByteArray(StandardCharsets.UTF_8),
            followRedirects = options?.optBoolean("followRedirects", true) ?: true,
            timeoutMs = policy.timeoutMs
        )
        val rawWanted = options?.optBoolean("raw", false) ?: false

        val result = try {
            fetcher.fetch(req)
        } catch (e: Exception) {
            FetchResult(0, "", emptyMap(), ByteArray(0), e.message ?: "请求异常")
        }

        synchronized(lock) {
            totalRequests++
            totalBytes += result.body.size
            lastRequestAt = System.currentTimeMillis()
            if (result.error != null) lastError = result.error
            val s = session(addr, pkg)
            s.lastActive = System.currentTimeMillis()
            sendResponse(s, id, result, rawWanted, reply)
        }
        log("fetch $pkg ${req.method} $url → ${if (result.error != null) "ERR ${result.error}" else result.status}")
    }

    /** 把 HTTP 结果编码成对端能解的帧；分片时按协商窗口推送 */
    private fun sendResponse(
        s: Session,
        id: String,
        result: FetchResult,
        rawWanted: Boolean,
        reply: (String) -> Unit
    ) {
        val neg = s.negotiated
        val original = result.body
        val compressed = compressIfNeeded(original, neg)
        val payload = compressed.second
        val compression = compressed.first

        val wantChunk = neg.chunked && payload.size > neg.chunkSize
        val headersJson = JSONObject()
        for ((k, v) in result.headers) headersJson.put(k, v)

        val resp = JSONObject()
            .put("ok", result.ok)
            .put("status", result.status)
            .put("statusText", result.statusText)
            .put("headers", headersJson)
            .put("raw", rawWanted)
            .put("truncated", result.truncated)
        if (result.error != null) resp.put("error", result.error)

        if (!wantChunk) {
            val (encoding, wire) = encodeSingle(payload, neg, rawWanted)
            if (wire.toByteArray(StandardCharsets.UTF_8).size > BridgeCaps.MAX_UNCHUNKED_WIRE_LEN) {
                // 未协商分片或边界情况：绝不能用一条蓝牙帧硬塞大响应。
                reply(errorResponse(id, "响应体过大，对端未协商分片").also { log("单帧超限，已拒绝") })
                return
            }
            resp.put("body", wire)
            resp.put("bodyEncoding", encoding)
            if (compression != "none") {
                resp.put("compression", compression)
                resp.put("originalBytes", original.size)
            }
            reply(JSONObject().put("tag", "fetch").put("id", id).put("resp", resp).toString())
            return
        }

        // 分片：先编码，再按「编码前字节数」切片
        val encoding = pickBinaryEncoding(neg)
        resp.put("body", "")
        resp.put("chunked", true)
        resp.put("totalBytes", payload.size)
        resp.put("chunkSize", neg.chunkSize)
        val count = (payload.size + neg.chunkSize - 1) / neg.chunkSize
        resp.put("chunkCount", count)
        resp.put("bodyEncoding", encoding)
        if (compression != "none") {
            resp.put("compression", compression)
            resp.put("originalBytes", original.size)
        }
        val useAck = neg.ackWindow > 0
        if (useAck) resp.put("ack", true)

        val chunks = ArrayList<String>(count)
        var off = 0
        while (off < payload.size) {
            val end = minOf(off + neg.chunkSize, payload.size)
            chunks.add(encode(payload.copyOfRange(off, end), encoding))
            off = end
        }
        reply(JSONObject().put("tag", "fetch").put("id", id).put("resp", resp).toString())

        val tx = ChunkTx(id, chunks, if (useAck) neg.ackWindow else Int.MAX_VALUE, reply)
        s.tx[id] = tx
        tx.pump()
        if (!useAck) s.tx.remove(id)
    }

    private fun ack(addr: String, pkg: String, obj: JSONObject) {
        synchronized(lock) {
            val s = sessions[key(addr, pkg)] ?: return
            s.lastActive = System.currentTimeMillis()
            val id = obj.optString("id")
            val value = obj.optInt("ack", 0)
            val tx = s.tx[id] ?: return
            tx.onAck(value)
            if (tx.done) s.tx.remove(id)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  编码                                                              */
    /* ------------------------------------------------------------------ */

    private fun compressIfNeeded(data: ByteArray, neg: Negotiated): Pair<String, ByteArray> {
        if (data.size < 256) return "none" to data
        if (!neg.compressions.contains("deflate")) return "none" to data
        return try {
            val def = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(8192)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            def.end()
            val packed = out.toByteArray()
            // 压不动就别压，否则还多一次解码开销
            if (packed.size >= data.size) "none" to data else "deflate" to packed
        } catch (e: Exception) {
            "none" to data
        }
    }

    private fun encodeSingle(payload: ByteArray, neg: Negotiated, rawWanted: Boolean): Pair<String, String> {
        if (!rawWanted && neg.encodings.contains("text") && isValidUtf8(payload)) {
            return "text" to String(payload, StandardCharsets.UTF_8)
        }
        val enc = pickBinaryEncoding(neg)
        return enc to encode(payload, enc)
    }

    private fun pickBinaryEncoding(neg: Negotiated): String =
        neg.encodings.firstOrNull { it == "base64" || it == "hex" } ?: "base64"

    private fun encode(bytes: ByteArray, encoding: String): String = when (encoding) {
        "hex" -> hex(bytes)
        "text" -> String(bytes, StandardCharsets.UTF_8)
        else -> Base64Codec.encode(bytes)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun hex(bytes: ByteArray): String {
        val table = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(table[v ushr 4]).append(table[v and 0x0F])
        }
        return sb.toString()
    }

    /* ------------------------------------------------------------------ */
    /*  状态 / 自检                                                        */
    /* ------------------------------------------------------------------ */

    fun stats(): JSONObject = synchronized(lock) {
        val arr = org.json.JSONArray()
        for (s in sessions.values) {
            arr.put(
                JSONObject()
                    .put("addr", s.addr)
                    .put("pkg", s.pkg)
                    .put("version", s.negotiated.version)
                    .put("chunked", s.negotiated.chunked)
                    .put("ackWindow", s.negotiated.ackWindow)
                    .put("idleMs", System.currentTimeMillis() - s.lastActive)
            )
        }
        JSONObject()
            .put("sessions", arr)
            .put("requests", totalRequests)
            .put("bytes", totalBytes)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("lastRequestAt", lastRequestAt)
            .put("policy", policy.toJson())
    }

    fun reset() = synchronized(lock) {
        sessions.clear()
        totalRequests = 0
        totalBytes = 0
        lastError = null
    }

    /**
     * 自检：把一组真实帧喂进引擎，断言输出符合规范。
     * 手机上没有手环也能验证「握手 / v1 单消息 / 分片 + ACK / SSRF 拦截」四条主路径。
     */
    fun selftest(fake: HttpFetcher? = null): JSONObject {
        val steps = org.json.JSONArray()
        var pass = 0
        var fail = 0
        fun step(name: String, ok: Boolean, detail: String) {
            if (ok) pass++ else fail++
            steps.put(JSONObject().put("name", name).put("ok", ok).put("detail", detail))
        }

        val engine = BridgeEngine(
            fake ?: object : HttpFetcher {
                override fun fetch(req: FetchRequest): FetchResult {
                    if (req.url.contains("big")) {
                        return FetchResult(200, "OK", mapOf("content-type" to "application/octet-stream"), ByteArray(9000) { (it % 251).toByte() })
                    }
                    return FetchResult(200, "OK", mapOf("content-type" to "application/json"), "{\"hello\":\"kldxst\"}".toByteArray())
                }
            },
            { BridgePolicy() },
            {}
        )
        val out = ArrayList<String>()
        val addr = "selftest"
        val pkg = "com.kldxst.selftest"

        // 1) 握手：count=0 → 回 count=1 且带 caps
        engine.onFrame(addr, pkg, "{\"tag\":\"__hs__\",\"count\":0,\"caps\":{\"version\":1,\"chunk\":false}}") { out.add(it) }
        val hs = out.lastOrNull()?.let { JSONObject(it) }
        step("握手回包", hs?.optString("tag") == "__hs__" && hs.optInt("count") == 1 && hs.optJSONObject("caps") != null,
            hs?.toString() ?: "无回包")

        // 2) count=2 不再回包
        val before = out.size
        engine.onFrame(addr, pkg, "{\"tag\":\"__hs__\",\"count\":2}") { out.add(it) }
        step("握手终止", out.size == before, "回包数 ${out.size - before}")

        // 3) v1 单消息 fetch
        engine.onFrame(
            addr, pkg,
            "{\"tag\":\"fetch\",\"id\":\"t1\",\"url\":\"https://selftest.local/json\",\"options\":{\"method\":\"GET\"}}"
        ) { out.add(it) }
        val r1 = out.lastOrNull()?.let { JSONObject(it) }?.optJSONObject("resp")
        step(
            "v1 单消息", r1 != null && r1.optInt("status") == 200 && r1.optString("body").trimStart().startsWith("{"),
            r1?.toString()?.take(120) ?: "无回包"
        )

        // 4) 分片 + ACK（声明 v3 能力的对端）
        out.clear()
        engine.onFrame(
            addr, pkg,
            "{\"tag\":\"__hs__\",\"count\":0,\"caps\":{\"version\":3,\"chunk\":true,\"ack\":true,\"maxChunkSize\":1024,\"encodings\":[\"hex\"],\"ackWindow\":2}}"
        ) { out.add(it) }
        engine.onFrame(
            addr, pkg,
            "{\"tag\":\"fetch\",\"id\":\"t2\",\"url\":\"https://selftest.local/big\",\"options\":{}}"
        ) { out.add(it) }
        val header = out.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("tag") == "fetch" }?.optJSONObject("resp")
        val chunked = header?.optBoolean("chunked") == true
        step("分片头", chunked && header!!.optInt("chunkCount") > 1, header?.toString()?.take(140) ?: "无头帧")
        // 逐片 ACK 直到收完
        var guard = 0
        while (guard++ < 64) {
            val chunks = out.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("tag") == "fetch-chunk" }
            val total = header?.optInt("chunkCount") ?: 0
            if (chunks.size >= total && total > 0) break
            val got = chunks.map { it.optInt("seq") }.toSet()
            var contiguous = 0
            while (got.contains(contiguous)) contiguous++
            engine.onFrame(addr, pkg, "{\"tag\":\"fetch-ack\",\"id\":\"t2\",\"ack\":$contiguous}") { out.add(it) }
        }
        val seqs = out.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("tag") == "fetch-chunk" }
            .map { it.optInt("seq") }
            .toSet()
        val total = header?.optInt("chunkCount") ?: 0
        step("分片收齐", total > 0 && seqs.size == total, "收到 ${seqs.size}/$total")

        // 5) SSRF：明文与内网都必须被拦
        out.clear()
        engine.onFrame(
            addr, pkg,
            "{\"tag\":\"fetch\",\"id\":\"t3\",\"url\":\"http://127.0.0.1/admin\",\"options\":{}}"
        ) { out.add(it) }
        val r3 = out.lastOrNull()?.let { JSONObject(it) }?.optJSONObject("resp")
        step("SSRF 拦截", r3 != null && !r3.optBoolean("ok"), r3?.optString("error") ?: "未拦截")

        return JSONObject()
            .put("ok", fail == 0)
            .put("pass", pass)
            .put("fail", fail)
            .put("steps", steps)
    }

    /* ------------------------------------------------------------------ */
    /*  内部结构                                                          */
    /* ------------------------------------------------------------------ */

    private fun key(addr: String, pkg: String) = "$addr|$pkg"

    private fun session(addr: String, pkg: String): Session {
        val k = key(addr, pkg)
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.lastActive > BridgeCaps.SESSION_IDLE_MS }
        val s = sessions.getOrPut(k) {
            Session(addr, pkg, Negotiated.of(PeerCaps(), policy))
        }
        s.tx.entries.removeAll { now - it.value.createdAt > BridgeCaps.TX_TIMEOUT_MS }
        return s
    }

    private fun errorResponse(id: String, message: String): String {
        val resp = JSONObject()
            .put("ok", false)
            .put("status", 0)
            .put("statusText", "")
            .put("headers", JSONObject())
            .put("body", "")
            .put("raw", false)
            .put("error", message)
        return JSONObject().put("tag", "fetch").put("id", id).put("resp", resp).toString()
    }

    private class Session(val addr: String, val pkg: String, var negotiated: Negotiated) {
        var peer: PeerCaps = PeerCaps()
        var lastActive: Long = System.currentTimeMillis()
        val tx = HashMap<String, ChunkTx>()
        val stamps = ArrayDeque<Long>()
    }

    /** 一次分片传输的状态机：累计 ACK + go-back-N（每个停滞点只重传一次） */
    private class ChunkTx(
        private val id: String,
        private val chunks: List<String>,
        private val window: Int,
        private val reply: (String) -> Unit
    ) {
        private var base = 0
        private var next = 0
        private var retransmitted = false
        val createdAt: Long = System.currentTimeMillis()
        val done: Boolean get() = base >= chunks.size

        fun pump() {
            val limit = if (window == Int.MAX_VALUE) chunks.size else minOf(chunks.size, base + window)
            while (next < limit) {
                reply(
                    JSONObject()
                        .put("tag", "fetch-chunk")
                        .put("id", id)
                        .put("seq", next)
                        .put("total", chunks.size)
                        .put("data", chunks[next])
                        .toString()
                )
                next++
            }
        }

        fun onAck(value: Int) {
            val ack = value.coerceIn(0, chunks.size)
            if (ack > base) {
                base = ack
                retransmitted = false
                if (!done) pump()
            } else if (next > base && !retransmitted) {
                next = base
                retransmitted = true
                pump()
            }
        }
    }
}

/** 自带的 Base64（java.util.Base64 要 API 26，minSdk 是 24） */
object Base64Codec {
    private const val T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(T[(n ushr 18) and 63]).append(T[(n ushr 12) and 63])
                .append(T[(n ushr 6) and 63]).append(T[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(T[(n ushr 18) and 63]).append(T[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(T[(n ushr 18) and 63]).append(T[(n ushr 12) and 63])
                    .append(T[(n ushr 6) and 63]).append("=")
            }
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filter { !it.isWhitespace() }
        val out = ByteArrayOutputStream(clean.length / 4 * 3)
        var buf = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            val v = T.indexOf(c)
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
