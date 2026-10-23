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
        .followRedirects(false)
        .build()

    suspend fun login(user: String, password: String): MiAccount.Credentials = withContext(Dispatchers.IO) {
        val (sign, qs) = step1()
        val auth = step2(user, password, sign, qs)
        val token = step3(auth)
        MiAccount.Credentials(
            userId = auth.optString("userId"),
            cUserId = auth.optString("cUserId"),
            ssecurity = auth.optString("ssecurity"),
            serviceToken = token,
            passToken = auth.optString("passToken"),
            deviceId = auth.optString("userId")
        )
    }

    /** 云端设备列表：did / 名称 / MAC / authkey */
    suspend fun devices(cred: MiAccount.Credentials): List<MiAccount.Device> = withContext(Dispatchers.IO) {
        val path = MiApi.PATH_DEVICES
        val query = ""
        val signed = MiSign.signedNonce(cred.ssecurity)
        val sign = MiSign.sign("GET", path, query, signed)
        val req = Request.Builder()
            .url(MiApi.HEALTH_HOST + path)
            .header("Cookie", cookie(cred))
            .header("_sign", sign)
            .header("signedNonce", signed)
            .header("User-Agent", UA)
            .get()
            .build()
        val body = exec(req, "设备列表")
        val arr = JSONObject(body).optJSONObject("data")?.optJSONArray("deviceList")
            ?: JSONObject(body).optJSONArray("deviceList")
            ?: org.json.JSONArray()
        val out = ArrayList<MiAccount.Device>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                MiAccount.Device(
                    did = o.optString("did"),
                    name = o.optString("name").ifBlank { o.optString("deviceName") },
                    mac = o.optString("mac").takeIf { it.isNotBlank() },
                    productDevice = o.optString("productDevice").takeIf { it.isNotBlank() },
                    authkey = o.optString("authkey").takeIf { it.isNotBlank() }
                )
            )
        }
        out
    }

    /* ------------------------------------------------------------------ */

    private fun step1(): Pair<String, String> {
        val url = MiApi.ACCOUNT_HOST + MiApi.PATH_LOGIN + "?sid=" + MiApi.SID + "&_json=true"
        val body = exec(Request.Builder().url(url).header("User-Agent", UA).get().build(), "serviceLogin")
        val o = json(strip(body))
        val sign = o.optString("_sign")
        val qs = o.optString("qs")
        log("serviceLogin 完成 _sign=${sign.take(8)}…")
        return sign to qs
    }

    private fun step2(user: String, password: String, sign: String, qs: String): JSONObject {
        val form = FormBody.Builder()
            .add("sid", MiApi.SID)
            .add("_json", "true")
            .add("qs", qs)
            .add("_sign", sign)
            .add("user", user)
            .add("hash", MiSign.md5Upper(password))
            .build()
        val req = Request.Builder()
            .url(MiApi.ACCOUNT_HOST + MiApi.PATH_LOGIN_AUTH2)
            .header("User-Agent", UA)
            .post(form)
            .build()
        val body = strip(exec(req, "serviceLoginAuth2"))
        val o = json(body)
        val code = o.optInt("code", -1)
        if (code == 70016) throw IllegalStateException("账号需要二次验证，请先在小米官网完成验证后重试")
        if (code != 0) {
            val desc = o.optString("desc").ifBlank { o.optString("description") }
            throw IllegalStateException("登录失败 code=$code ${desc}")
        }
        if (o.optString("ssecurity").isBlank()) throw IllegalStateException("登录响应缺少 ssecurity")
        log("serviceLoginAuth2 完成 userId=${o.optString("userId")}")
        return o
    }

    private fun step3(auth: JSONObject): String {
        val location = auth.optString("location")
        if (location.isBlank()) throw IllegalStateException("登录响应缺少 location")
        val req = Request.Builder()
            .url(location)
            .header("User-Agent", UA)
            .header(
                "Cookie",
                "cUserId=${auth.optString("cUserId")}; userId=${auth.optString("userId")}; " +
                    "passToken=${auth.optString("passToken")}"
            )
            .get()
            .build()
        val body = strip(exec(req, "sts"))
        val token = json(body).optString("serviceToken")
        if (token.isBlank()) throw IllegalStateException("sts 未返回 serviceToken")
        log("sts 完成 serviceToken 长度 ${token.length}")
        return token
    }

    private fun cookie(cred: MiAccount.Credentials): String =
        "serviceToken=${cred.serviceToken}; userId=${cred.userId}; cUserId=${cred.cUserId}"

    private fun exec(req: Request, step: String): String {
        http.newCall(req).execute().use { resp ->
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
