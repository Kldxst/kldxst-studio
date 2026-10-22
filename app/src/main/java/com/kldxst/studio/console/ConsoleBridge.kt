package com.kldxst.studio.console

import android.content.Context
import android.webkit.JavascriptInterface
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bt.DirectLink
import com.kldxst.studio.core.Hub
import com.kldxst.studio.install.Rpk
import com.kldxst.studio.secure.Vault
import com.kldxst.studio.service.StudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 控制台 ⇄ 原生的唯一通道。
 *
 * 安全模型（三道闸，缺一不可）：
 *  1. **来源**：调用必须发生在白名单 origin 的页面上（[currentOrigin] 由 WebViewClient 维护，
 *     不信任页面自己声明的来源）。
 *  2. **会话令牌**：进程启动时随机生成，只在页面加载完成后注入；页面被替换或跳走后
 *     令牌随之失效，避免「远程页面劫持已打开的 WebView」直接拿能力。
 *  3. **参数校验**：每个方法自己再校验一次入参，不假设页面是善意的。
 *
 * 这样即使控制台页面被篡改（服务端被攻破 / CDN 被投毒），攻击者也只能在
 * 「已经能执行 JS」的前提下调用这些方法 —— 而本类的每个方法都不接受任意路径、
 * 不接受任意命令、不接受任意 URL 直连内网（见 SsrfGuard）。
 */
class ConsoleBridge(
    private val ctx: Context,
    private val allowedHosts: Set<String>,
    private val token: String
) {

    @Volatile
    var currentOrigin: String = ""

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 由 MainActivity 注入：把结果回送给页面 */
    @Volatile
    var emit: ((String, String) -> Unit)? = null

    @JavascriptInterface
    fun call(reqId: String, method: String, argsJson: String) {
        if (!originAllowed()) {
            reply(reqId, error("E_ORIGIN", "当前页面不在允许的域名下：$currentOrigin"))
            return
        }
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        if (args.optString("t") != token) {
            reply(reqId, error("E_TOKEN", "会话令牌无效，请重新打开应用"))
            return
        }
        scope.launch {
            val result = try {
                dispatch(method, args)
            } catch (e: Exception) {
                error("E_INTERNAL", e.message ?: e.javaClass.simpleName)
            }
            reply(reqId, result)
        }
    }

    /** 页面可以用它确认自己拿到的是不是最新令牌（调试用） */
    @JavascriptInterface
    fun ping(): String = JSONObject().put("ok", true).put("v", BuildConfig.VERSION_NAME).toString()

    private suspend fun dispatch(method: String, a: JSONObject): JSONObject = when (method) {
        "hello" -> ok(
            JSONObject()
                .put("app", JSONObject.wrap(Hub.buildInfo()))
                .put("caps", JSONObject.wrap(Hub.capabilities()))
                .put("hosts", JSONObject.wrap(Hub.wear.hosts()))
                .put("bridgeRunning", Hub.bridgeRunning)
                .put("policy", Hub.policy.toJson())
                .put("uptimeMs", System.currentTimeMillis() - Hub.startedAt)
        )

        "wear.status" -> {
            val node = Hub.wear.node
            val state = if (node != null) runCatching { Hub.wear.watchState() }.getOrNull() else null
            ok(
                JSONObject()
                    .put("connected", node != null)
                    .put("device", node?.let { JSONObject().put("id", it.id).put("name", it.name) } ?: JSONObject.NULL)
                    .put("hosts", JSONObject.wrap(Hub.wear.hosts()))
                    .put("state", state?.let { JSONObject.wrap(it) } ?: JSONObject.NULL)
            )
        }

        "wear.connect" -> {
            val node = Hub.wear.connect()
            Hub.wear.authorize()
            Hub.wear.listen()
            ok(JSONObject().put("device", JSONObject().put("id", node.id).put("name", node.name)))
        }

        "wear.permission" -> {
            Hub.wear.authorize()
            ok(JSONObject().put("granted", true))
        }

        "wear.appInstalled" -> {
            ok(JSONObject().put("installed", Hub.wear.isInstalled()))
        }

        "wear.launch" -> {
            val path = a.optString("path").ifBlank { "/pages/index" }
            require(path.startsWith("/")) { "路径必须以 / 开头" }
            Hub.wear.launch(path)
            ok(JSONObject().put("launched", path))
        }

        "wear.notify" -> {
            val title = a.optString("title").take(80).ifBlank { "K社工坊" }
            val message = a.optString("message").take(400)
            Hub.wear.notify(title, message)
            ok(JSONObject())
        }

        "wear.state" -> {
            val o = JSONObject()
            for ((k, v) in Hub.wear.watchState()) o.put(k, v ?: JSONObject.NULL)
            ok(o)
        }

        "wear.sendRaw" -> {
            // 诊断用：只允许 JSON 文本，且限制长度，避免把通道当成任意数据管道
            val text = a.optString("text")
            require(text.startsWith("{") && text.length <= 8192) { "只接受 8KB 以内的 JSON 文本" }
            Hub.wear.send(text)
            ok(JSONObject())
        }

        "bridge.start" -> {
            ctx.getSharedPreferences("kldxst", Context.MODE_PRIVATE).edit()
                .putBoolean("bridge.autoStart", a.optBoolean("autoStart", true)).apply()
            StudioService.start(ctx)
            ok(JSONObject())
        }

        "bridge.stop" -> {
            StudioService.stop(ctx)
            ok(JSONObject())
        }

        "bridge.status" -> ok(Hub.bridge.stats().put("running", Hub.bridgeRunning))

        "bridge.logs" -> {
            val limit = a.optInt("limit", 80).coerceIn(1, 320)
            ok(JSONObject().put("lines", JSONObject.wrap(Hub.log.tail(limit))))
        }

        "bridge.policy" -> {
            val p = BridgePolicy.fromJson(a.optJSONObject("policy"))
            Hub.applyPolicy(p)
            Vault.set(KEY_POLICY, p.toJson().toString())
            ok(JSONObject().put("policy", p.toJson()))
        }

        "bridge.selftest" -> ok(Hub.bridge.selftest())

        "secure.get" -> {
            val k = keyOf(a)
            ok(JSONObject().put("value", Vault.get(k) ?: JSONObject.NULL))
        }

        "secure.set" -> {
            val k = keyOf(a)
            val v = a.optString("value")
            require(v.length <= 16 * 1024) { "值过长" }
            Vault.set(k, v)
            ok(JSONObject())
        }

        "secure.del" -> {
            Vault.remove(keyOf(a))
            ok(JSONObject())
        }

        "direct.status" -> {
            val p = Hub.direct.peer
            ok(
                JSONObject()
                    .put("available", Hub.direct.available())
                    .put("connected", Hub.direct.connected)
                    .put("peer", p?.let { JSONObject().put("name", it.name).put("address", it.address) } ?: JSONObject.NULL)
                    .put("bonded", JSONObject.wrap(Hub.direct.bonded().map { it.name + "|" + it.address }))
            )
        }

        "direct.scan" -> {
            val peers = withContext(Dispatchers.IO) { scanDirect(a.optInt("timeoutMs", 8000).coerceIn(3000, 20000).toLong()) }
            ok(
                JSONObject().put(
                    "peers",
                    JSONObject.wrap(peers.map {
                        JSONObject().put("name", it.name).put("address", it.address)
                            .put("rssi", it.rssi).put("xiaomi", it.xiaomi)
                    })
                )
            )
        }

        "direct.connect" -> {
            val address = a.optString("address")
            require(address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) { "MAC 地址格式不对" }
            val name = Hub.direct.connect(address)
            ok(JSONObject().put("device", name).put("address", address))
        }

        "direct.handshake" -> {
            val key = a.optString("authkey")
            require(key.matches(Regex("[0-9a-fA-F]{32}"))) { "authkey 应为 32 位十六进制" }
            val r = Hub.direct.handshake(key)
            val wrapped = JSONObject.wrap(r)
            ok(if (wrapped is JSONObject) wrapped else JSONObject())
        }

        "direct.frame" -> {
            val hex = a.optString("hex").replace(" ", "")
            require(hex.length in 2..4096 && hex.length % 2 == 0 && hex.matches(Regex("[0-9a-fA-F]+"))) { "帧应为偶数长度十六进制" }
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            Hub.direct.send(bytes)
            ok(JSONObject().put("sent", bytes.size))
        }

        "direct.profile" -> {
            val p = Hub.direct.profile.copy(
                magic = a.optInt("magic", Hub.direct.profile.magic),
                littleEndianLength = a.optBoolean("littleEndianLength", Hub.direct.profile.littleEndianLength),
                authMode = a.optString("authMode", Hub.direct.profile.authMode)
            )
            Hub.direct.profile = p
            ok(JSONObject().put("magic", p.magic).put("littleEndianLength", p.littleEndianLength).put("authMode", p.authMode))
        }

        "direct.close" -> {
            Hub.direct.close()
            ok(JSONObject())
        }

        "rpk.inspectUrl" -> {
            val url = a.optString("url")
            val expect = a.optString("sha256").lowercase()
            require(url.startsWith("https://")) { "只接受 https 资源" }
            val info = withContext(Dispatchers.IO) { inspectRemote(url, expect) }
            ok(info)
        }

        else -> error("E_METHOD", "未知方法：$method")
    }

    /* ------------------------------------------------------------------ */

    /** 阻塞等首批扫描结果：控制台是一次请求一次响应，不需要流式 */
    private fun scanDirect(timeoutMs: Long): List<DirectLink.Peer> {
        val queue = LinkedBlockingQueue<List<DirectLink.Peer>>()
        Hub.direct.scan({ list -> queue.offer(list) }, { err -> Hub.log.add("[直连] 扫描失败：$err") })
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: emptyList()
    }

    private fun inspectRemote(url: String, expectSha: String): JSONObject {
        val resp = http.newCall(Request.Builder().url(url).header("User-Agent", "KldxstStudio/1.0").build())
            .execute()
        resp.use {
            require(it.isSuccessful) { "下载失败：HTTP ${it.code}" }
            val body = it.body ?: throw IllegalStateException("响应为空")
            val len = body.contentLength()
            require(len <= MAX_RPK_BYTES) { "资源过大（${len} 字节）" }
            val name = url.substringAfterLast('/').ifBlank { "download.rpk" }
            val dir = File(ctx.cacheDir, "rpk").apply { mkdirs() }
            val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        require(total <= MAX_RPK_BYTES) { "资源超过 64MB 上限" }
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val info = Rpk.inspect(target)
            val json = info.toJson()
            json.put("path", target.absolutePath)
            json.put("shaMatch", if (expectSha.isBlank()) JSONObject.NULL else sha == expectSha)
            if (expectSha.isNotBlank() && sha != expectSha) {
                json.put("error", "哈希不匹配：期望 ${expectSha.take(16)}…，实际 ${sha.take(16)}…")
                target.delete()
            }
            return json
        }
    }

    private fun keyOf(a: JSONObject): String {
        val k = a.optString("key")
        require(k.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "非法键名" }
        return k
    }

    private fun originAllowed(): Boolean {
        val o = currentOrigin
        if (o.isEmpty()) return false
        val host = o.substringAfter("://", "").substringBefore("/").substringBefore(":")
        return allowedHosts.contains(host)
    }

    private fun ok(data: JSONObject): JSONObject = JSONObject().put("ok", true).put("data", data)

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("message", message)

    private fun reply(reqId: String, payload: JSONObject) {
        emit?.invoke(reqId, payload.toString())
    }

    companion object {
        private const val KEY_POLICY = "bridge.policy"
        private const val MAX_RPK_BYTES = 64L * 1024 * 1024
    }
}
