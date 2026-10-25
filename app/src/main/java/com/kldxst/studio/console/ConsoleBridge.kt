package com.kldxst.studio.console

import android.Manifest
import android.util.Base64
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
            ok(
                JSONObject()
                    .put("device", dev.toJson().put("authkey", key))
                    .put("address", dev.mac ?: JSONObject.NULL)
            )
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

        "device.status" -> ok(Hub.wearController.statusJson())

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
            val key = a.optString("authkey")
                .takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }
                ?: Vault.get("authkey")
            val name = Hub.wearController.connect(address, transport, key)
            // 记住这台设备：前台服务重连时要用
            Vault.set("device.address", address.uppercase())
            Vault.set("device.transport", Hub.wear.transport)
            if (!key.isNullOrBlank()) Vault.set("authkey", key)
            // 用户主动连接即视为要保持连接；没有通知权限时服务内部会安全降级。
            StudioService.start(ctx)
            ok(Hub.wearController.statusJson().put("device", name).put("transport", Hub.wear.transport))
        }

        "device.auth" -> {
            val key = a.optString("authkey").ifBlank { Vault.get("authkey") ?: "" }
            require(key.matches(Regex("[0-9a-fA-F]{32}"))) { "authkey 应为 32 位十六进制" }
            Hub.wearController.authenticate(key)
            Vault.set("authkey", key)
            ok(Hub.wearController.statusJson())
        }

        "device.refresh" -> {
            Hub.wearController.refresh()
            ok(Hub.wearController.statusJson())
        }

        "device.close" -> {
            prefs().edit().putBoolean("bridge.autoStart", false).apply()
            StudioService.stop(ctx)
            Hub.wearController.disconnect()
            ok(JSONObject())
        }

        "device.frames" -> {
            val limit = a.optInt("limit", 40).coerceIn(1, 200)
            ok(JSONObject().put("frames", JSONObject.wrap(Hub.log.tail(limit))))
        }

        "device.syncTime" -> {
            Hub.wearController.syncTime(a.optBoolean("is24Hour", true))
            ok(JSONObject())
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

        /** 通知：真实 NotifyData protobuf，date 用本地 yyyyMMdd'T'HHmmss。 */
        "notify.push" -> {
            val title = a.optString("title").take(64)
            val body = a.optString("body").take(1200)
            val appName = a.optString("appName", "KdSync").take(48)
            Hub.wearController.notify(title, body, appName)
            ok(JSONObject())
        }

        "apps.list" -> {
            Hub.wearController.loadApps()
            ok(Hub.wearController.statusJson())
        }

        "apps.launch" -> {
            val pkg = a.optString("package")
            require(pkg.isNotBlank()) { "缺少包名" }
            val app = Hub.wearController.apps.firstOrNull { it.packageName == pkg }
                ?: throw IllegalStateException("设备应用列表里没有 $pkg")
            Hub.wearController.launch(app, a.optString("uri"))
            ok(JSONObject())
        }

        "apps.remove" -> {
            val pkg = a.optString("package")
            val app = Hub.wearController.apps.firstOrNull { it.packageName == pkg }
                ?: throw IllegalStateException("设备应用列表里没有 $pkg")
            Hub.wearController.uninstall(app)
            ok(Hub.wearController.statusJson())
        }

        /**
         * 本地文件安装：页面把 .rpk 读成 base64（WebView 文件选择器），
         * 原生落盘 + Rpk 体检后再走 ThirdpartyApp PREPARE → MASS → 结果。
         */
        "apps.installBase64" -> withContext(Dispatchers.IO) {
            val b64 = a.optString("data")
            require(b64.isNotBlank()) { "文件内容为空" }
            require(b64.length <= 90 * 1024 * 1024) { "文件超过 64MB 上限" }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val name = a.optString("name", "package.rpk").replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(ctx.cacheDir, "rpk").apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            val info = Rpk.inspect(file)
            info.error?.let { throw IllegalStateException(it) }
            val pkg = a.optString("package").ifBlank { info.packageName ?: "" }
            require(pkg.isNotBlank()) { "无法从 manifest 读取包名，请手动填写" }
            val result = Hub.wearController.installQuickApp(pkg, bytes)
            ok(JSONObject()
                .put("package", pkg)
                .put("result", result.code)
                .put("message", result.message)
                .put("status", Hub.wearController.statusJson()))
        }

        "apps.installPath" -> withContext(Dispatchers.IO) {
            val path = a.optString("path")
            val root = File(ctx.cacheDir, "rpk").canonicalFile
            val file = File(path).canonicalFile
            require(file.path.startsWith(root.path + File.separator)) { "只允许安装缓存目录内的体检文件" }
            require(file.isFile && file.length() <= 64L * 1024 * 1024) { "文件不存在或超过 64MB" }
            val info = Rpk.inspect(file)
            info.error?.let { throw IllegalStateException(it) }
            val pkg = a.optString("package").ifBlank { info.packageName ?: "" }
            require(pkg.isNotBlank()) { "无法从 manifest 读取包名" }
            val result = Hub.wearController.installQuickApp(pkg, file.readBytes())
            ok(JSONObject()
                .put("package", pkg)
                .put("result", result.code)
                .put("message", result.message)
                .put("status", Hub.wearController.statusJson()))
        }

        "watchfaces.list" -> {
            Hub.wearController.loadWatchFaces()
            ok(Hub.wearController.statusJson())
        }

        "watchfaces.set" -> {
            val id = a.optString("id")
            require(id.isNotBlank()) { "缺少表盘 id" }
            Hub.wearController.setWatchFace(id)
            ok(JSONObject())
        }

        "watchfaces.remove" -> {
            val id = a.optString("id")
            require(id.isNotBlank()) { "缺少表盘 id" }
            Hub.wearController.removeWatchFace(id)
            ok(JSONObject())
        }

        "watchfaces.installBase64" -> withContext(Dispatchers.IO) {
            val id = a.optString("id")
            require(id.isNotBlank()) { "缺少表盘 id" }
            val b64 = a.optString("data")
            require(b64.isNotBlank()) { "文件内容为空" }
            require(b64.length <= 90 * 1024 * 1024) { "文件超过 64MB 上限" }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val result = Hub.wearController.installWatchFace(id, bytes)
            ok(JSONObject()
                .put("id", id)
                .put("result", result.code)
                .put("message", result.message)
                .put("status", Hub.wearController.statusJson()))
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
