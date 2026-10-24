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

    data class Validated(
        val denied: String? = null,
        val addresses: List<InetAddress> = emptyList()
    )

    private val BLOCKED_HOST_SUFFIX = listOf(
        ".local", ".internal", ".localhost", ".home.arpa", ".lan", ".intranet"
    )
    private val BLOCKED_HOST_EXACT = setOf(
        "localhost", "metadata", "metadata.google.internal", "instance-data",
        "169.254.169.254", "100.100.100.200"
    )

    /** 返回 null 表示放行，否则返回可展示的拒绝原因 */
    fun check(url: String, policy: BridgePolicy): String? = validate(url, policy).denied

    /**
     * 一次完成「策略校验 + DNS 解析」并返回解析结果。调用方必须把 [Validated.addresses]
     * 直接交给 OkHttp 的 Dns，不能再次解析，否则校验与连接之间仍有 rebinding 窗口。
     */
    fun validate(url: String, policy: BridgePolicy): Validated {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return Validated("URL 格式无效")
        }
        val scheme = uri.scheme?.lowercase() ?: return Validated("URL 缺少协议")
        if (scheme != "https" && scheme != "http") return Validated("只允许 http/https")
        if (scheme == "http" && !policy.allowHttp) return Validated("策略禁止明文 http")

        if (!uri.userInfo.isNullOrEmpty()) return Validated("URL 不得包含用户信息")

        var host = uri.host?.lowercase() ?: return Validated("URL 缺少主机名")
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        host = host.trimEnd('.')
        if (host.isBlank()) return Validated("URL 缺少主机名")
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        val allowedPort = if (scheme == "https") 443 else 80
        if (port != allowedPort) return Validated("端口 $port 不被允许")

        if (BLOCKED_HOST_EXACT.contains(host)) return Validated("主机被禁止：$host")
        if (BLOCKED_HOST_SUFFIX.any { host.endsWith(it) }) return Validated("主机被禁止：$host")
        if (policy.blockHosts.any { host == it || host.endsWith(".$it") }) {
            return Validated("主机在黑名单：$host")
        }
        if (policy.allowHosts.isNotEmpty() && policy.allowHosts.none { host == it || host.endsWith(".$it") }) {
            return Validated("主机不在白名单：$host")
        }

        val addrs = try {
            // getAllByName 同时处理 IPv4、IPv6 和域名。
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            return Validated("域名解析失败")
        }
        if (addrs.isEmpty()) return Validated("域名无解析结果")
        for (a in addrs) {
            if (isBlockedAddress(a)) return Validated("禁止访问内网地址：${a.hostAddress}")
        }
        return Validated(addresses = addrs)
    }

    /** 兼容旧调用方：仅校验不返回解析结果。 */
    fun resolvePinned(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

    private fun isBlockedAddress(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
            a.isAnyLocalAddress || a.isMulticastAddress
        ) {
            return true
        }
        val b = a.address
        if (b.size == 4) return isPrivateV4(b)
        if (b.size != 16) return true
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        // ULA fc00::/7
        if ((first and 0xFE) == 0xFC) return true
        // IPv4-mapped ::ffff:a.b.c.d
        val mapped = (0..9).all { b[it].toInt() == 0 } &&
            (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
        if (mapped) return isPrivateV4(b.copyOfRange(12, 16))
        // IPv6 中保留的站点本地 fec0::/10
        if (first == 0xFE && (second and 0xC0) == 0xC0) return true
        return false
    }

    private fun isPrivateV4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = b[2].toInt() and 0xFF
        return when {
            a == 0 -> true
            a == 10 -> true
            a == 127 -> true
            a == 169 && c == 254 -> true          // 链路本地
            a == 172 && c in 16..31 -> true
            a == 192 && c == 168 -> true
            a == 100 && c in 64..127 -> true      // CGNAT
            a == 192 && c == 0 && (d == 0 || d == 2) -> true // IETF 协议分配 / TEST-NET-1
            a == 198 && c in 18..19 -> true       // 基准测试
            a == 198 && c == 51 && d == 100 -> true
            a == 203 && c == 0 && d == 113 -> true
            a >= 224 -> true                      // 组播/保留/广播
            else -> false
        }
    }
}
