package com.kldxst.studio.console

import android.content.Context
import android.webkit.JavascriptInterface
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.account.MiAccount
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bt.FrameCodec
import com.kldxst.studio.bt.MiWear
import com.kldxst.studio.bt.ProtocolProfile
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
 * 安全模型（三道闸）：来源白名单 → 会话令牌 → 每个方法自行校验入参。
 * 直连架构下这里只做三件事：蓝牙收发、帧编解码、把结果如实回给页面。
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

    @Volatile
    var emit: ((String, String) -> Unit)? = null

    @JavascriptInterface
    fun call(reqId: String, method: String, argsJson: String) {
        if (!originAllowed()) {
            reply(reqId, error("E_ORIGIN", "当前页面不在允许的域名下"))
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

    @JavascriptInterface
    fun ping(): String = JSONObject().put("ok", true).put("v", BuildConfig.VERSION_NAME).toString()

    private suspend fun dispatch(method: String, a: JSONObject): JSONObject = when (method) {
        "hello" -> ok(
            JSONObject()
                .put("app", JSONObject.wrap(Hub.buildInfo()))
                .put("caps", JSONObject.wrap(Hub.capabilities()))
                .put("protocol", Hub.protocol.toJson())
                .put("bridgeRunning", Hub.bridgeRunning)
                .put("policy", Hub.policy.toJson())
                .put("uptimeMs", System.currentTimeMillis() - Hub.startedAt)
        )

        "account.status" -> {
            val c = Hub.cred
            ok(
                JSONObject()
                    .put("loggedIn", c != null)
                    .put("userId", c?.userId ?: JSONObject.NULL)
                    .put("savedAddress", Vault.get("device.address") ?: JSONObject.NULL)
                    .put("hasAuthkey", !Vault.get("authkey").isNullOrBlank())
            )
        }

        "account.login" -> {
            val user = a.optString("user").trim()
            val pass = a.optString("password")
            require(user.isNotBlank() && pass.isNotBlank()) { "账号和密码都要填" }
            val cred = Hub.account.login(user, pass)
            Hub.cred = cred
            Vault.set("mi.cred", JSONObject()
                .put("userId", cred.userId)
                .put("cUserId", cred.cUserId)
                .put("ssecurity", cred.ssecurity)
                .put("serviceToken", cred.serviceToken)
                .put("passToken", cred.passToken)
                .put("deviceId", cred.deviceId)
                .toString())
            val list = Hub.account.devices(cred)
            ok(JSONObject().put("userId", cred.userId).put("devices", JSONObject.wrap(list.map { it.toJson() })))
        }

        "account.devices" -> {
            val c = Hub.cred ?: throw IllegalStateException("请先登录小米账号")
            val list = Hub.account.devices(c)
            ok(JSONObject().put("devices", JSONObject.wrap(list.map { it.toJson() })))
        }

        /** 选中云端设备：把 mac 与 authkey 落到本机，后续连接用它 */
        "account.use" -> {
            val c = Hub.cred ?: throw IllegalStateException("请先登录小米账号")
            val did = a.optString("did")
            require(did.isNotBlank()) { "缺少 did" }
            val dev = Hub.account.devices(c).firstOrNull { it.did == did }
                ?: throw IllegalStateException("云端没有这台设备")
            val key = dev.authkey ?: throw IllegalStateException("这台设备没有返回 authkey")
            require(key.matches(Regex("[0-9a-fA-F]{32}"))) { "authkey 格式异常" }
            Vault.set("authkey", key)
            dev.mac?.let { Vault.set("device.address", it.uppercase()) }
            ok(JSONObject().put("device", dev.toJson()).put("address", dev.mac ?: JSONObject.NULL))
        }

        "account.logout" -> {
            Hub.cred = null
            Vault.remove("mi.cred")
            ok(JSONObject())
        }

        "protocol.get" -> ok(Hub.protocol.toJson())

        "protocol.set" -> {
            val p = ProtocolProfile.fromJson(a.optJSONObject("profile"))
            Hub.applyProtocol(p)
            Vault.set(KEY_PROTOCOL, p.toJson().toString())
            ok(p.toJson())
        }

        "device.status" -> {
            val peer = Hub.wear.peer
            ok(
                JSONObject()
                    .put("available", Hub.wear.available())
                    .put("connected", Hub.wear.connected)
                    .put("transport", Hub.wear.transport)
                    .put("authenticated", Hub.wear.authenticated)
                    .put("peer", peer?.let {
                        JSONObject().put("name", it.name).put("address", it.address)
                    } ?: JSONObject.NULL)
                    .put("bonded", JSONObject.wrap(Hub.wear.bonded().map { it.name + "|" + it.address }))
                    .put("ops", JSONObject.wrap(Hub.wear.ops()))
            )
        }

        "device.scan" -> {
            val ms = a.optInt("durationMs", 8000).coerceIn(3000, 20000).toLong()
            val peers = withContext(Dispatchers.IO) { scanOnce(ms) }
            ok(JSONObject().put("peers", JSONObject.wrap(peers.map {
                JSONObject()
                    .put("name", it.name).put("address", it.address)
                    .put("rssi", it.rssi).put("xiaomi", it.xiaomi)
                    .put("productId", it.productId ?: JSONObject.NULL)
            })))
        }

        "device.connect" -> {
            val address = a.optString("address")
            require(address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) { "MAC 地址格式不对" }
            val transport = a.optString("transport", "spp").lowercase()
            val name = if (transport == "ble") Hub.wear.connectGatt(address) else Hub.wear.connectSpp(address)
            // 记住这台设备：前台服务重连时要用
            Vault.set("device.address", address)
            Vault.set("device.transport", Hub.wear.transport)
            ok(JSONObject().put("device", name).put("transport", Hub.wear.transport))
        }

        "device.auth" -> {
            val key = a.optString("authkey")
            require(key.matches(Regex("[0-9a-fA-F]{32}"))) { "authkey 应为 32 位十六进制" }
            ok(Hub.wear.auth(key))
        }

        "device.close" -> {
            Hub.wear.close()
            ok(JSONObject())
        }

        "device.frames" -> {
            val limit = a.optInt("limit", 40).coerceIn(1, 200)
            ok(JSONObject().put("frames", JSONObject.wrap(Hub.wear.recent(limit))))
        }

        /** 原始帧：校准期手动发一帧，把回包原样拿回来看 */
        "device.raw" -> {
            val hex = a.optString("hex").replace(" ", "")
            require(hex.length in 4..8192 && hex.length % 2 == 0 && hex.matches(Regex("[0-9a-fA-F]+"))) {
                "帧应为偶数长度十六进制"
            }
            val bytes = FrameCodec.unhex(hex) ?: throw IllegalArgumentException("十六进制解析失败")
            Hub.wear.send(bytes)
            ok(JSONObject().put("sent", bytes.size))
        }

        /** 业务命令：消息号由 profile.ops 决定，payload 由页面给 */
        "op.run" -> {
            val op = a.optString("op")
            require(op.isNotBlank()) { "缺少 op" }
            val payloadHex = a.optString("payloadHex")
            val payload = if (payloadHex.isBlank()) ByteArray(0)
            else FrameCodec.unhex(payloadHex) ?: throw IllegalArgumentException("payloadHex 解析失败")
            val timeout = a.optLong("timeoutMs", 6000L).coerceIn(500, 30000)
            ok(Hub.wear.command(op, payload, timeout))
        }

        /** 通知：文本按 UTF-8 直接进 payload（多数固件用 JSON 文本承载） */
        "notify.push" -> {
            val title = a.optString("title").take(64)
            val body = a.optString("body").take(400)
            val json = JSONObject().put("title", title).put("body", body).put("at", System.currentTimeMillis())
            ok(Hub.wear.command("notify", FrameCodec.utf8(json.toString()), 4000))
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

        "secure.get" -> ok(JSONObject().put("value", Vault.get(keyOf(a)) ?: JSONObject.NULL))
        "secure.set" -> {
            val v = a.optString("value")
            require(v.length <= 16 * 1024) { "值过长" }
            Vault.set(keyOf(a), v)
            ok(JSONObject())
        }
        "secure.del" -> {
            Vault.remove(keyOf(a))
            ok(JSONObject())
        }

        "rpk.inspectUrl" -> {
            val url = a.optString("url")
            val expect = a.optString("sha256").lowercase()
            require(url.startsWith("https://")) { "只接受 https 资源" }
            ok(withContext(Dispatchers.IO) { inspectRemote(url, expect) })
        }

        else -> error("E_METHOD", "未知方法：$method")
    }

    /* ------------------------------------------------------------------ */

    private fun scanOnce(timeoutMs: Long): List<MiWear.Peer> {
        val queue = LinkedBlockingQueue<List<MiWear.Peer>>()
        Hub.wear.scan({ list -> queue.offer(list) }, { err -> Hub.log.add("扫描失败：$err") }, timeoutMs)
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: emptyList()
    }

    private fun inspectRemote(url: String, expectSha: String): JSONObject {
        http.newCall(Request.Builder().url(url).header("User-Agent", "KldxstStudio/1.0").build())
            .execute().use { resp ->
                require(resp.isSuccessful) { "下载失败：HTTP ${resp.code}" }
                val body = resp.body ?: throw IllegalStateException("响应为空")
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
                val json = Rpk.inspect(target).toJson()
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
        private const val KEY_PROTOCOL = "protocol.profile"
        private const val MAX_RPK_BYTES = 64L * 1024 * 1024
    }
}
