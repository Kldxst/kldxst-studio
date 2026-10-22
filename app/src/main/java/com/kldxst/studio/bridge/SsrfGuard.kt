package com.kldxst.studio.bridge

import java.net.InetAddress
import java.net.URI

/**
 * 网桥的出口守卫。
 *
 * 快应用可以请求任意 URL，所以这里必须按「不可信输入」处理：一旦允许
 * 访问回环/内网/链路本地地址，快应用（或篡改了 rpk 的人）就能把手机当成
 * 跳板去摸路由器管理页、云主机元数据接口、局域网里的其他设备。
 *
 * 规则：
 *  1. 只允许 https（除非策略显式放开 http，且仅限白名单域名）
 *  2. 禁止 URL 里带 userinfo（http://a@b/ 这类会把真实主机藏在 @ 后面）
 *  3. 禁止非常规端口（https 只允许 443；http 放开时只允许 80）
 *  4. 域名解析后任一地址落在私有/回环/链路本地/组播段即拒绝
 *  5. 命中策略黑名单、或白名单非空且未命中，一律拒绝
 *
 * 已知残余风险：校验与 OkHttp 的解析之间存在 TOCTOU 窗口（DNS rebinding）。
 * 缓解手段是把解析结果直接交给 OkHttp 的 Dns 实现，见 OkHttpFetcher。
 */
object SsrfGuard {

    private val BLOCKED_HOST_SUFFIX = listOf(
        ".local", ".internal", ".localhost", ".home.arpa", ".lan", ".intranet"
    )
    private val BLOCKED_HOST_EXACT = setOf(
        "localhost", "metadata", "metadata.google.internal", "instance-data",
        "169.254.169.254", "100.100.100.200"
    )

    /** 返回 null 表示放行，否则返回可展示的拒绝原因 */
    fun check(url: String, policy: BridgePolicy): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "URL 格式无效"
        }
        val scheme = uri.scheme?.lowercase() ?: return "URL 缺少协议"
        if (scheme != "https" && scheme != "http") return "只允许 http/https"
        if (scheme == "http" && !policy.allowHttp) return "策略禁止明文 http"

        if (!uri.userInfo.isNullOrEmpty()) return "URL 不得包含用户信息"

        val host = uri.host?.lowercase() ?: return "URL 缺少主机名"
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        val allowedPort = if (scheme == "https") 443 else 80
        if (port != allowedPort) return "端口 $port 不被允许"

        if (BLOCKED_HOST_EXACT.contains(host)) return "主机被禁止：$host"
        if (BLOCKED_HOST_SUFFIX.any { host.endsWith(it) }) return "主机被禁止：$host"
        if (policy.blockHosts.any { host == it || host.endsWith(".$it") }) return "主机在黑名单：$host"
        if (policy.allowHosts.isNotEmpty() && policy.allowHosts.none { host == it || host.endsWith(".$it") }) {
            return "主机不在白名单：$host"
        }

        // 字面量 IP 直接判段；域名则解析后逐地址判
        val literal = parseIpv4(host)
        if (literal != null) {
            if (isPrivate(literal)) return "禁止访问内网地址"
            return null
        }
        val addrs = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return "域名解析失败"
        }
        if (addrs.isEmpty()) return "域名无解析结果"
        for (a in addrs) {
            if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
                a.isAnyLocalAddress || a.isMulticastAddress || isPrivate(a.address)
            ) {
                return "域名指向内网地址：${a.hostAddress}"
            }
        }
        return null
    }

    /** 解析结果缓存：给 OkHttp 的 Dns 用，把「校验过的地址」直接钉死，消除 rebinding 窗口 */
    fun resolvePinned(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v < 0 || v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun isPrivate(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = b[2].toInt() and 0xFF
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 0 -> true
            a == 169 && c == 254 -> true          // 链路本地
            a == 172 && c in 16..31 -> true
            a == 192 && c == 168 -> true
            a == 100 && c in 64..127 -> true      // CGNAT
            a == 192 && c == 0 && d == 0 -> true
            a >= 224 -> true                      // 组播/保留
            else -> false
        }
    }
}
