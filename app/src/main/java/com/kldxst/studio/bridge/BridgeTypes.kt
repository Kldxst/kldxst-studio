package com.kldxst.studio.bridge

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 网桥的数据类型与策略。
 *
 * 这一层刻意不引用任何 android.* 类型，这样协议用例可以在 JVM 单测里直接跑
 * （app/src/test 下的 BridgeEngineTest 就是这么验证握手的）。
 */

/** 一次 HTTP 请求的抽象，便于单测替换掉真实网络 */
data class FetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val followRedirects: Boolean = true,
    val timeoutMs: Int = 20000
)

data class FetchResult(
    val status: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val error: String? = null,
    /** 响应体超过策略上限，body 中是前 N 字节 */
    val truncated: Boolean = false
) {
    val ok: Boolean get() = error == null && status in 200..299
}

interface HttpFetcher {
    fun fetch(req: FetchRequest): FetchResult
}

/**
 * 网桥策略。**全部字段都可以由服务端下发**（/api/config），
 * 所以调整白名单、限额、超时不需要重新打包 APK。
 */
data class BridgePolicy(
    /** 允许使用网桥的快应用包名；null 表示不限制（默认限制为本机已确认的包名） */
    val allowPackages: Set<String>? = null,
    /** 允许访问的域名（含子域）；非空时即为白名单模式 */
    val allowHosts: Set<String> = emptySet(),
    val blockHosts: Set<String> = emptySet(),
    /** 是否允许明文 http（默认关闭；只有明确配置的内网调试场景才该打开） */
    val allowHttp: Boolean = false,
    /** 单次响应体上限，超出即截断并标记 */
    val maxResponseBytes: Int = 8 * 1024 * 1024,
    val timeoutMs: Int = 20000,
    /** 每分钟最多请求数，防止快应用死循环把手机流量打光 */
    val maxRequestsPerMinute: Int = 240,
    /** 单分片字节数（编码前） */
    val chunkSize: Int = 4096,
    /** 在途分片窗口 */
    val ackWindow: Int = 4
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("allowPackages", allowPackages?.let { JSONObject.wrap(it.toList()) } ?: JSONObject.NULL)
        put("allowHosts", JSONObject.wrap(allowHosts.toList()))
        put("blockHosts", JSONObject.wrap(blockHosts.toList()))
        put("allowHttp", allowHttp)
        put("maxResponseBytes", maxResponseBytes)
        put("timeoutMs", timeoutMs)
        put("maxRequestsPerMinute", maxRequestsPerMinute)
        put("chunkSize", chunkSize)
        put("ackWindow", ackWindow)
    }

    companion object {
        /**
         * 部分更新：控制台只提交用户改过的字段时，缺失字段沿用 [fallback]，
         * 不会把 allowPackages、chunkSize 等策略悄悄重置成默认值。
         */
        fun fromJson(o: JSONObject?, fallback: BridgePolicy = BridgePolicy()): BridgePolicy {
            if (o == null) return fallback
            fun normalize(s: String): String = s.trim().trimEnd('.').lowercase()
            fun strSet(key: String, old: Set<String>): Set<String> {
                if (!o.has(key)) return old
                val a = o.optJSONArray(key) ?: return emptySet()
                val out = LinkedHashSet<String>()
                for (i in 0 until a.length()) {
                    normalize(a.optString(i)).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
                return out
            }
            val packages = when {
                !o.has("allowPackages") -> fallback.allowPackages
                o.isNull("allowPackages") -> null
                else -> {
                    val a = o.optJSONArray("allowPackages") ?: return fallback.copy(allowPackages = emptySet())
                    val out = LinkedHashSet<String>()
                    for (i in 0 until a.length()) {
                        a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    }
                    out
                }
            }
            return BridgePolicy(
                allowPackages = packages,
                allowHosts = strSet("allowHosts", fallback.allowHosts),
                blockHosts = strSet("blockHosts", fallback.blockHosts),
                allowHttp = if (o.has("allowHttp")) o.optBoolean("allowHttp", false) else fallback.allowHttp,
                maxResponseBytes = o.optInt("maxResponseBytes", fallback.maxResponseBytes)
                    .coerceIn(64 * 1024, 32 * 1024 * 1024),
                timeoutMs = o.optInt("timeoutMs", fallback.timeoutMs).coerceIn(2000, 60000),
                maxRequestsPerMinute = o.optInt("maxRequestsPerMinute", fallback.maxRequestsPerMinute)
                    .coerceIn(10, 6000),
                chunkSize = o.optInt("chunkSize", fallback.chunkSize).coerceIn(256, 65536),
                ackWindow = o.optInt("ackWindow", fallback.ackWindow).coerceIn(1, 64)
            )
        }
    }
}

/** 协议版本常量。与服务端 FetchBridge/PROTOCOL.md 对齐 */
object BridgeCaps {
    const val LOCAL_VERSION = 4
    const val LOCAL_CHUNK = true
    const val LOCAL_ACK = true
    // 不声明 stream：v4 流式路径只在双方都声明时才启用，本端选择不分块流，
    // 让客户端走已经冻结的 v1-v3 语义，行为可预测。
    const val LOCAL_STREAM = false
    // 默认 MiWear 帧只有 8KB；这里按 UTF-8 字节算，避免中文文本按字符数误判。
    const val MAX_UNCHUNKED_WIRE_LEN = 7000
    const val MIN_CHUNK = 256
    const val MAX_CHUNK = 4096
    const val SESSION_IDLE_MS = 600_000L
    const val TX_TIMEOUT_MS = 120_000L
}

data class PeerCaps(
    val version: Int = 1,
    val chunk: Boolean = false,
    val ack: Boolean = false,
    val stream: Boolean = false,
    val maxChunkSize: Int = 4096,
    val encodings: List<String> = emptyList(),
    val compressions: List<String> = emptyList(),
    val ackWindow: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("chunk", chunk)
        put("ack", ack)
        put("stream", stream)
        put("maxChunkSize", maxChunkSize)
        put("encodings", JSONObject.wrap(encodings))
        put("compressions", JSONObject.wrap(compressions))
        put("ackWindow", ackWindow)
    }

    companion object {
        fun fromJson(o: JSONObject?): PeerCaps {
            if (o == null) return PeerCaps()
            fun list(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                val out = ArrayList<String>(a.length())
                for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
                return out
            }
            return PeerCaps(
                version = o.optInt("version", 1).coerceIn(1, 4),
                chunk = o.optBoolean("chunk", false),
                ack = o.optBoolean("ack", false),
                stream = o.optBoolean("stream", false),
                maxChunkSize = o.optInt("maxChunkSize", 4096).coerceIn(BridgeCaps.MIN_CHUNK, BridgeCaps.MAX_CHUNK),
                encodings = list("encodings"),
                compressions = list("compressions"),
                ackWindow = o.optInt("ackWindow", 0)
            )
        }
    }
}

/** 协商结果 */
data class Negotiated(
    val version: Int,
    val chunked: Boolean,
    val chunkSize: Int,
    val ackWindow: Int,
    val stream: Boolean,
    val encodings: List<String>,
    val compressions: List<String>
) {
    companion object {
        val LOCAL_ENCODINGS = listOf("base64", "hex", "text")
        val LOCAL_COMPRESSIONS = listOf("none", "deflate")

        fun of(peer: PeerCaps, policy: BridgePolicy): Negotiated {
            val version = minOf(peer.version, BridgeCaps.LOCAL_VERSION)
            val chunked = version >= 2 && peer.chunk && BridgeCaps.LOCAL_CHUNK
            val chunkSize = if (chunked) {
                peer.maxChunkSize.coerceIn(BridgeCaps.MIN_CHUNK, BridgeCaps.MAX_CHUNK)
                    .coerceAtMost(policy.chunkSize.coerceIn(BridgeCaps.MIN_CHUNK, BridgeCaps.MAX_CHUNK))
            } else 0
            // 对端没声明 encodings 时按 v1 基线处理
            val enc = if (peer.encodings.isEmpty()) listOf("base64", "text")
            else peer.encodings.filter { LOCAL_ENCODINGS.contains(it) }.ifEmpty { listOf("base64") }
            val comp = if (peer.compressions.isEmpty()) listOf("none")
            else peer.compressions.filter { LOCAL_COMPRESSIONS.contains(it) }.ifEmpty { listOf("none") }
            val ackWindow = if (version >= 3 && chunked && peer.ack && BridgeCaps.LOCAL_ACK) {
                (if (peer.ackWindow <= 0) policy.ackWindow else peer.ackWindow)
                    .coerceIn(1, 64)
                    .coerceAtMost(policy.ackWindow.coerceIn(1, 64))
            } else 0
            return Negotiated(
                version = version,
                chunked = chunked,
                chunkSize = chunkSize,
                ackWindow = ackWindow,
                stream = false,
                encodings = enc,
                compressions = comp
            )
        }
    }
}

/** 简易环形日志：控制台只拉最近 N 行，不占内存 */
class RingLog(private val capacity: Int = 240) {
    private val lines = ArrayDeque<String>(capacity)
    private val seq = AtomicLong()

    @Synchronized
    fun add(text: String) {
        val n = seq.incrementAndGet()
        lines.addLast("#$n ${text}")
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun tail(limit: Int): List<String> = lines.toList().takeLast(limit.coerceIn(1, capacity))

    @Synchronized
    fun clear() = lines.clear()
}
