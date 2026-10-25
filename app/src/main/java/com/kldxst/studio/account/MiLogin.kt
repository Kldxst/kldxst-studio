package com.kldxst.studio.account

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 小米账号登录 → 取设备列表与 authkey。
 *
 * 全流程四步，每一步失败都带 HTTP 状态和响应片段，便于定位：
 *   1. serviceLogin        取 _sign / qs
 *   2. serviceLoginAuth2   账号密码换 ssecurity（二次验证会返回 code=70016）
 *   3. sts                 换 serviceToken
 *   4. bledevice_info      取 did / mac / authkey
 */
class MiLogin(private val log: (String) -> Unit = {}) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var lastHeaders: okhttp3.Headers? = null

    suspend fun login(user: String, password: String): MiAccount.Credentials = withContext(Dispatchers.IO) {
        val deviceId = randomDeviceId()
        val (sign, qs) = step1(deviceId)
        val auth = step2(user, password, sign, qs, deviceId)
        val token = step3(auth, deviceId)
        MiAccount.Credentials(
            userId = auth.optString("userId"),
            cUserId = auth.optString("cUserId").ifBlank {
                cookieValue("cUserId").orEmpty()
            },
            ssecurity = auth.optString("ssecurity"),
            serviceToken = token,
            passToken = auth.optString("passToken"),
            deviceId = deviceId
        )
    }

    /** 云端设备列表：did / 名称 / MAC / authkey */
    suspend fun devices(cred: MiAccount.Credentials): List<MiAccount.Device> = withContext(Dispatchers.IO) {
        val path = MiApi.PATH_DEVICES
        val query = ""
        val nonce = MiSign.nonce()
        val signed = MiSign.signedNonce(cred.ssecurity, nonce)
        val sign = MiSign.sign("GET", path, query, signed)
        val req = Request.Builder()
            .url(MiApi.HEALTH_HOST + path)
            .header("Cookie", cookie(cred))
            .header("_sign", sign)
            .header("_nonce", nonce)
            .header("signedNonce", signed)
            .header("region_tag", "cn")
            .header("HandleParams", "true")
            .header("User-Agent", UA)
            .get()
            .build()
        val body = exec(req, "设备列表")
        val root = JSONObject(body)
        val data = root.optJSONObject("data")
        val arr = data?.optJSONArray("deviceList")
            ?: data?.optJSONArray("list")
            ?: root.optJSONArray("deviceList")
            ?: org.json.JSONArray()
        val out = ArrayList<MiAccount.Device>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nested = o.optJSONObject("deviceData") ?: o.optJSONObject("extra")
            fun field(name: String): String =
                o.optString(name).ifBlank { nested?.optString(name).orEmpty() }.trim()
            val authkey = field("authkey").ifBlank { field("authKey") }
            out.add(
                MiAccount.Device(
                    did = field("did").ifBlank { field("deviceId") },
                    name = field("name").ifBlank { field("deviceName") },
                    mac = field("mac").ifBlank { field("macAddress") }.takeIf { it.isNotBlank() },
                    productDevice = field("productDevice").takeIf { it.isNotBlank() },
                    authkey = authkey.takeIf { it.isNotBlank() }
                )
            )
        }
        out
    }

    /* ------------------------------------------------------------------ */

    private fun step1(deviceId: String): Pair<String, String> {
        val url = MiApi.ACCOUNT_HOST + MiApi.PATH_LOGIN + "?sid=" + MiApi.SID + "&_json=true"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=$deviceId; locale=zh_CN")
            .get()
            .build()
        val body = exec(req, "serviceLogin")
        val o = json(strip(body))
        val sign = o.optString("_sign")
        if (sign.isBlank()) throw IllegalStateException("serviceLogin 未返回 _sign；账号可能已失效")
        val qs = o.optString("qs").ifBlank { "?sid=${MiApi.SID}&_json=true" }
        log("serviceLogin 完成 _sign=${sign.take(8)}…")
        return sign to qs
    }

    private fun step2(user: String, password: String, sign: String, qs: String, deviceId: String): JSONObject {
        val form = FormBody.Builder()
            .add("sid", MiApi.SID)
            .add("hash", MiSign.md5Upper(password))
            .add("callback", "https://sts-hlth.io.mi.com/healthapp/sts")
            .add("qs", qs)
            .add("user", user)
            .add("_sign", sign)
            .add("_json", "true")
            .build()
        val req = Request.Builder()
            .url(MiApi.ACCOUNT_HOST + MiApi.PATH_LOGIN_AUTH2)
            .header("User-Agent", UA)
            .header("Cookie", "sdkVersion=accountsdk-18.8.15; deviceId=$deviceId; locale=zh_CN")
            .post(form)
            .build()
        val body = strip(exec(req, "serviceLoginAuth2"))
        val o = json(body)
        val code = o.optInt("code", -1)
        if (code == 70016) throw IllegalStateException("账号或密码错误（code=70016）")
        if (code != 0) {
            val desc = o.optString("desc").ifBlank { o.optString("description") }
            throw IllegalStateException("登录失败 code=$code ${desc}")
        }
        if (o.optString("ssecurity").isBlank()) throw IllegalStateException("登录响应缺少 ssecurity")
        if (o.optString("notificationUrl").isNotBlank()) {
            throw IllegalStateException("账号需要二次验证，请先在小米官网完成验证后重试")
        }
        log("serviceLoginAuth2 完成 userId=${o.optString("userId")}")
        return o
    }

    private fun step3(auth: JSONObject, deviceId: String): String {
        val location = auth.optString("location")
        if (location.isBlank()) throw IllegalStateException("登录响应缺少 location")
        if (!location.startsWith("https://", ignoreCase = true)) {
            throw IllegalStateException("登录跳转不是 HTTPS，已拒绝")
        }
        val host = runCatching { java.net.URI(location).host?.lowercase() }.getOrNull()
        if (host == null || !(host == "xiaomi.com" || host.endsWith(".xiaomi.com") ||
                host == "mi.com" || host.endsWith(".mi.com") || host.endsWith(".mi.com.cn"))
        ) {
            throw IllegalStateException("登录跳转地址不在小米域名下，已拒绝")
        }
        val req = Request.Builder()
            .url(location)
            .header("User-Agent", UA)
            .header(
                "Cookie",
                "sdkVersion=accountsdk-18.8.15; deviceId=$deviceId; " +
                    "cUserId=${auth.optString("cUserId")}; userId=${auth.optString("userId")}; " +
                    "passToken=${auth.optString("passToken")}"
            )
            .get()
            .build()
        val body = strip(exec(req, "sts"))
        var token = cookieValue("serviceToken").orEmpty()
        if (token.isBlank()) token = runCatching { json(body).optString("serviceToken") }.getOrDefault("")
        if (token.isBlank()) throw IllegalStateException("sts 未返回 serviceToken")
        log("sts 完成 serviceToken 长度 ${token.length}")
        return token
    }

    private fun cookieValue(name: String): String? {
        val headers = lastHeaders ?: return null
        for (value in headers.values("Set-Cookie")) {
            val first = value.substringBefore(';')
            val k = first.substringBefore('=', "").trim()
            if (k.equals(name, ignoreCase = true)) return first.substringAfter('=', "").trim()
        }
        return null
    }

    private fun randomDeviceId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun cookie(cred: MiAccount.Credentials): String =
        "sdkVersion=accountsdk-18.8.15; deviceId=${cred.deviceId}; " +
            "sdkVersion=accountsdk-18.8.15; deviceId=${cred.deviceId}; " +
            "serviceToken=${cred.serviceToken}; userId=${cred.userId}; cUserId=${cred.cUserId}"

    private fun exec(req: Request, step: String): String {
        http.newCall(req).execute().use { resp ->
            lastHeaders = resp.headers
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                log("$step HTTP ${resp.code}：${text.take(120)}")
                throw IllegalStateException("$step 失败 HTTP ${resp.code}")
            }
            return text
        }
    }

    /** 小米接口在 JSON 前加 `&&&START&&&` */
    private fun strip(s: String): String = s.substringAfter("&&&START&&&", s)

    private fun json(s: String): JSONObject =
        runCatching { JSONObject(s) }.getOrElse { throw IllegalStateException("响应不是 JSON：${s.take(120)}") }

    companion object {
        const val UA = "Android; MiFit"
    }
}
