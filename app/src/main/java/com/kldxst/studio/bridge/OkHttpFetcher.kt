package com.kldxst.studio.bridge

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 真实出口：OkHttp + SsrfGuard。
 *
 * 三个必须自己处理、不能交给默认行为的点：
 *  1. 重定向手动跟随 —— 默认跟随会把 Authorization/Cookie 一路带到新主机去
 *  2. 跨源跳转必须剥凭据（与浏览器 Fetch 语义一致）
 *  3. DNS 用「已校验过的地址」钉死，避免校验后又被解析到内网（rebinding）
 */
class OkHttpFetcher(private val policyProvider: () -> BridgePolicy) : HttpFetcher {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    override fun fetch(req: FetchRequest): FetchResult {
        val policy = policyProvider()
        var current = req
        var hops = 0
        while (true) {
            val result = single(current, policy)
            if (result.error != null) return result
            val location = result.headers.entries.firstOrNull { it.key.equals("location", true) }?.value
            val isRedirect = result.status == 301 || result.status == 302 || result.status == 303 ||
                result.status == 307 || result.status == 308
            if (!req.followRedirects || !isRedirect || location.isNullOrBlank()) return result
            if (++hops > 10) return FetchResult(0, "", emptyMap(), ByteArray(0), "重定向次数过多")

            val next = try {
                URI(current.url).resolve(location).toString()
            } catch (e: Exception) {
                return FetchResult(0, "", emptyMap(), ByteArray(0), "重定向地址无效")
            }
            val sameOrigin = originOf(next) == originOf(current.url)
            val nextMethod = when (result.status) {
                303 -> if (current.method.equals("HEAD", true)) "HEAD" else "GET"
                301, 302 -> if (current.method.equals("POST", true)) "GET" else current.method
                else -> current.method
            }
            val keepBody = nextMethod == current.method && current.body != null
            val headers = if (sameOrigin) current.headers else current.headers.filterKeys {
                val k = it.lowercase()
                k != "authorization" && k != "cookie" && k != "proxy-authorization" && k != "host"
            }
            current = FetchRequest(
                url = next,
                method = nextMethod,
                headers = headers,
                body = if (keepBody) current.body else null,
                followRedirects = true,
                timeoutMs = current.timeoutMs
            )
        }
    }

    private fun single(req: FetchRequest, policy: BridgePolicy): FetchResult {
        val method = req.method.uppercase()
        if (method !in ALLOWED_METHODS) {
            return FetchResult(0, "", emptyMap(), ByteArray(0), "不支持的请求方法：$method")
        }

        // validate 返回的地址直接钉进 Dns，后续不会再次解析。
        val validated = SsrfGuard.validate(req.url, policy)
        if (validated.denied != null) {
            return FetchResult(0, "", emptyMap(), ByteArray(0), validated.denied)
        }
        val builder = try {
            Request.Builder().url(req.url)
        } catch (e: Exception) {
            return FetchResult(0, "", emptyMap(), ByteArray(0), "URL 格式无效")
        }
        var hasUa = false
        for ((k, v) in req.headers) {
            if (k.equals("host", true) || k.equals("content-length", true)) continue
            if (k.equals("user-agent", true)) hasUa = true
            try {
                builder.header(k, v)
            } catch (e: Exception) {
                // 非法头名直接丢弃，不要让整个请求失败
            }
        }
        if (!hasUa) builder.header("User-Agent", "KldxstStudio/1.0 (wearable bridge)")
        val raw = req.body
        val contentType = req.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        // GET/HEAD 不允许带 body，否则 OkHttp 直接抛 IllegalArgumentException
        val body = if (raw == null || method == "GET" || method == "HEAD") null
        else raw.toRequestBody(contentType?.toMediaTypeOrNull())
        builder.method(method, body)

        val pinned = validated.addresses
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = pinned
        }
        val perCall = client.newBuilder()
            .dns(dns)
            .callTimeout(req.timeoutMs.coerceIn(2000, 60000).toLong(), TimeUnit.MILLISECONDS)
            .build()

        return try {
            perCall.newCall(builder.build()).execute().use { resp ->
                val headers = LinkedHashMap<String, String>()
                for (name in resp.headers.names()) {
                    headers[name.lowercase()] = resp.headers.values(name).joinToString(", ")
                }
                val out = java.io.ByteArrayOutputStream(minOf(policy.maxResponseBytes, 64 * 1024))
                var total = 0
                var truncated = false
                resp.body?.byteStream()?.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val room = policy.maxResponseBytes - total
                        if (room <= 0) {
                            truncated = true
                            break
                        }
                        val take = minOf(n, room)
                        out.write(buf, 0, take)
                        total += take
                        if (take < n) {
                            truncated = true
                            break
                        }
                    }
                }
                FetchResult(
                    status = resp.code,
                    statusText = resp.message,
                    headers = headers,
                    body = out.toByteArray(),
                    error = null,
                    truncated = truncated
                )
            }
        } catch (e: Exception) {
            FetchResult(0, "", emptyMap(), ByteArray(0), e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }

    private fun originOf(url: String): String = try {
        val u = URI(url)
        "${u.scheme}://${u.host}:${if (u.port == -1) (if (u.scheme == "https") 443 else 80) else u.port}"
    } catch (e: Exception) {
        ""
    }
}
