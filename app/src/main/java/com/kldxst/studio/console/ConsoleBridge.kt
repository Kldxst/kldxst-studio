package com.kldxst.studio.console

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.account.MiAccount
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bridge.FetchRequest
import com.kldxst.studio.bridge.OkHttpFetcher
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 控制台 ⇄ 原生的唯一通道。
 *
 * 安全模型（三道闸）：来源白名单 → 会话令牌 → 每个方法自行校验入参。
 * 直连架构下这里只做三件事：蓝牙收发、帧编解码、把结果如实回给页面。
 */
class ConsoleBridge(
    private val ctx: Context,
    private val allowedHosts: Set<String>,
    private val token: String,
    private val onRequestPermissions: () -> Unit = {},
    private val onOpenExternal: (String) -> Unit = {},
    private val onOpenNotificationSettings: () -> Unit = {},
    private val onOpenAppSettings: () -> Unit = {}
) {

    @Volatile
    var currentOrigin: String = ""

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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

    /**
     * 页面脚本在 document-start 注入不可用时，用这个同步方法取回本次进程的令牌。
     * 这里仍先校验当前页面 origin，非白名单页面拿到的是空串。
     */
    @JavascriptInterface
    fun sessionToken(): String = if (originAllowed()) token else ""

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
                .put("permissions", permissionStatus())
                .put("uptimeMs", System.currentTimeMillis() - Hub.startedAt)
        )

        "permissions.status" -> ok(permissionStatus())

        "permissions.request" -> {
            onRequestPermissions()
            ok(permissionStatus().put("requested", true))
        }

        "permissions.openSettings" -> {
            onOpenAppSettings()
            ok(JSONObject())
        }

        /** 通知使用权是系统特殊权限，只能跳到系统的通知使用权列表 */
        "permissions.notificationAccess" -> {
            onOpenNotificationSettings()
            ok(JSONObject())
        }

        "app.openExternal" -> {
            val url = a.optString("url")
            val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
            require(scheme == "https" || scheme == "http") { "只允许打开 http/https 链接" }
            onOpenExternal(url)
            ok(JSONObject())
        }

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
            Vault.set(Hub.KEY_CRED, JSONObject()
                .put("userId", cred.userId)
                .put("cUserId", cred.cUserId)
                .put("ssecurity", cred.ssecurity)
                .put("serviceToken", cred.serviceToken)
                .put("passToken", cred.passToken)
                .put("deviceId", cred.deviceId)
                .toString())
            var warning: String? = null
            val list = runCatching { Hub.account.devices(cred) }.getOrElse { e ->
                Hub.log.add("设备列表获取失败：${e.message}")
                warning = "登录成功，但设备列表获取失败：${e.message}"
                emptyList()
            }
            val data = JSONObject()
                .put("userId", cred.userId)
                .put("devices", JSONObject.wrap(list.map { it.toJson() }))
            if (warning != null) data.put("warning", warning)
            ok(data)
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
            Vault.remove(Hub.KEY_CRED)
            ok(JSONObject())
        }

        "protocol.get" -> ok(Hub.protocol.toJson())

        "protocol.set" -> {
            val p = ProtocolProfile.fromJson(a.optJSONObject("profile"))
            Hub.applyProtocol(p)
            Vault.set(Hub.KEY_PROTOCOL, p.toJson().toString())
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
                    .put("authState", Hub.wear.authState)
                    .put("lastAuthReply", Hub.wear.lastAuthReply ?: JSONObject.NULL)
                    .put("peer", peer?.let {
                        JSONObject().put("name", it.name).put("address", it.address)
                    } ?: JSONObject.NULL)
                    .put("bonded", JSONObject.wrap(Hub.wear.bonded().map { it.name + "|" + it.address }))
                    .put("ops", JSONObject.wrap(Hub.wear.ops()))
            )
        }

        "device.scan" -> {
            val ms = a.optInt("durationMs", 8000).coerceIn(3000, 20000).toLong()
            val outcome = withContext(Dispatchers.IO) { scanOnce(ms) }
            if (outcome.error != null) throw IllegalStateException(outcome.error)
            ok(JSONObject().put("peers", JSONObject.wrap(outcome.peers.map {
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
            Vault.set("device.address", address.uppercase())
            Vault.set("device.transport", Hub.wear.transport)
            // 用户主动连接即视为要保持连接；没有通知权限时服务内部会安全降级。
            StudioService.start(ctx)
            ok(JSONObject().put("device", name).put("transport", Hub.wear.transport))
        }

        "device.auth" -> {
            val key = a.optString("authkey")
            require(key.matches(Regex("[0-9a-fA-F]{32}"))) { "authkey 应为 32 位十六进制" }
            ok(Hub.wear.auth(key))
        }

        "device.close" -> {
            prefs().edit().putBoolean("bridge.autoStart", false).apply()
            StudioService.stop(ctx)
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
            prefs().edit().putBoolean("bridge.autoStart", a.optBoolean("autoStart", true)).apply()
            StudioService.start(ctx)
            ok(JSONObject())
        }

        "bridge.stop" -> {
            prefs().edit().putBoolean("bridge.autoStart", false).apply()
            StudioService.stop(ctx)
            ok(JSONObject())
        }

        "notify.get" -> ok(
            JSONObject()
                .put("relay", prefs().getBoolean("notify.relay", true))
                .put("ignoreOngoing", prefs().getBoolean("notify.ignoreOngoing", true))
                .put("access", hasNotificationAccess())
        )

        "notify.set" -> {
            prefs().edit()
                .putBoolean("notify.relay", a.optBoolean("relay", true))
                .putBoolean("notify.ignoreOngoing", a.optBoolean("ignoreOngoing", true))
                .apply()
            ok(JSONObject().put("access", hasNotificationAccess()))
        }

        "bridge.status" -> ok(Hub.bridge.stats().put("running", Hub.bridgeRunning))

        "bridge.logs" -> {
            val limit = a.optInt("limit", 80).coerceIn(1, 320)
            ok(JSONObject().put("lines", JSONObject.wrap(Hub.log.tail(limit))))
        }

        "bridge.policy" -> {
            val p = BridgePolicy.fromJson(a.optJSONObject("policy"), Hub.policy)
            Hub.applyPolicy(p)
            Vault.set(Hub.KEY_POLICY, p.toJson().toString())
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

    private data class ScanOutcome(val peers: List<MiWear.Peer>, val error: String? = null)

    private fun scanOnce(timeoutMs: Long): ScanOutcome {
        val queue = LinkedBlockingQueue<ScanOutcome>()
        val error = AtomicReference<String?>(null)
        Hub.wear.scan({ _ -> }, { err ->
            error.set(err)
            Hub.log.add("扫描失败：$err")
        }, timeoutMs) { peers ->
            val failure = error.get()
            queue.offer(ScanOutcome(if (failure == null) peers else emptyList(), failure))
        }
        return queue.poll(timeoutMs + 3_000, TimeUnit.MILLISECONDS)
            ?: ScanOutcome(emptyList(), "扫描超时，请关闭其他蓝牙应用后重试")
    }

    private fun inspectRemote(url: String, expectSha: String): JSONObject {
        require(expectSha.isBlank() || expectSha.matches(Regex("[0-9a-fA-F]{64}"))) {
            "期望 SHA-256 应为 64 位十六进制"
        }
        // 与网桥共用出口守卫和 DNS 钉扎，避免用户填写的 URL 借重定向访问内网。
        val policy = BridgePolicy(
            maxResponseBytes = MAX_RPK_BYTES.toInt(),
            timeoutMs = 60_000,
            allowHttp = false
        )
        val result = OkHttpFetcher { policy }.fetch(
            FetchRequest(url = url, method = "GET", timeoutMs = 60_000)
        )
        require(result.error == null) { result.error ?: "下载失败" }
        require(result.status in 200..299) { "下载失败：HTTP ${result.status}，请填写最终下载地址" }
        require(!result.truncated) { "资源超过 64MB 上限" }

        val name = Uri.parse(url).lastPathSegment?.ifBlank { "download.rpk" } ?: "download.rpk"
        val dir = File(ctx.cacheDir, "rpk").apply { mkdirs() }
        val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        target.writeBytes(result.body)
        val sha = MessageDigest.getInstance("SHA-256").digest(result.body)
            .joinToString("") { "%02x".format(it) }
        val json = Rpk.inspect(target).toJson()
        json.put("path", target.absolutePath)
        json.put("shaMatch", if (expectSha.isBlank()) JSONObject.NULL else sha.equals(expectSha, true))
        if (expectSha.isNotBlank() && !sha.equals(expectSha, true)) {
            json.put("error", "哈希不匹配：期望 ${expectSha.take(16)}…，实际 ${sha.take(16)}…")
            target.delete()
        }
        return json
    }

    private fun keyOf(a: JSONObject): String {
        val k = a.optString("key")
        require(k.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "非法键名" }
        return k
    }

    private fun permissionStatus(): JSONObject {
        val scanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val connectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        return JSONObject()
            .put("bluetoothScan", scanGranted)
            .put("bluetoothConnect", connectGranted)
            .put("notifications", notificationGranted)
            .put("notificationAccess", hasNotificationAccess())
            .put("bluetoothAvailable", Hub.wear.available())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private fun prefs() = ctx.getSharedPreferences("kldxst", Context.MODE_PRIVATE)

    private fun originAllowed(): Boolean {
        val o = currentOrigin
        if (o.isBlank()) return false
        val uri = runCatching { Uri.parse(o) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return allowedHosts.contains(host)
    }

    private fun ok(data: JSONObject): JSONObject = JSONObject().put("ok", true).put("data", data)

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("error", code).put("message", message)

    private fun reply(reqId: String, payload: JSONObject) {
        emit?.invoke(reqId, payload.toString())
    }

    fun close() {
        emit = null
        scope.cancel()
    }

    companion object {
        private const val MAX_RPK_BYTES = 64L * 1024 * 1024
    }
}
