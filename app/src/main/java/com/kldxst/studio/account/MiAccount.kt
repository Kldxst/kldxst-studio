package com.kldxst.studio.account

import android.util.Base64
import com.kldxst.studio.bt.FrameCodec
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
 * 小米账号登录与设备授权获取。
 *
 * 端点与字段来自对 AstroBox 2.2.0 的分析（`libapp_lib.so` 内的字符串）：
 *   https://account.xiaomi.com/pass/serviceLogin?sid=…
 *   https://account.xiaomi.com/pass/serviceLoginAuth2
 *   https://sts-hlth.io.mi.com/healthapp/sts?sid=miothealth&_json=true
 *   https://hlth.io.mi.com/healthapp/device/bledevice_info
 *   https://hlth.io.mi.com/app/v1/source/get_source_list
 * 登录后的凭据结构（user_id / device_id / ssecurity / service_token / c_user_id /
 * pass_token / psecurity / updated_at）与那边保存的 `StoredXiaomiCredentials` 一致。
 *
 * 这套接口属于小米账号体系，参数与签名规则可能随服务端变化：
 * 每一步都把 HTTP 状态与响应片段写进日志，失败时能定位到具体是哪一步。
 */
class MiAccount(private val log: (String) -> Unit = {}) {

    data class Credentials(
        val userId: String,
        val cUserId: String,
        val ssecurity: String,
        val serviceToken: String,
        val passToken: String,
        val deviceId: String
    )

    data class Device(
        val did: String,
        val name: String,
        val mac: String?,
        val productDevice: String?,
        val authkey: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("did", did)
            .put("name", name)
            .put("mac", mac ?: JSONObject.NULL)
            .put("productDevice", productDevice ?: JSONObject.NULL)
            .put("hasAuthkey", !authkey.isNullOrBlank())
    }
}

/**
 * 协议常量集中在这里，便于服务端或后续版本调整。
 */
object MiApi {
    const val SID = "miothealth"
    const val ACCOUNT_HOST = "https://account.xiaomi.com"
    const val STS_HOST = "https://sts-hlth.io.mi.com"
    const val HEALTH_HOST = "https://hlth.io.mi.com"

    const val PATH_LOGIN = "/pass/serviceLogin"
    const val PATH_LOGIN_AUTH2 = "/pass/serviceLoginAuth2"
    const val PATH_STS = "/healthapp/sts"
    const val PATH_DEVICES = "/healthapp/device/bledevice_info"
}

/**
 * 小米开放接口的签名方案（与 miio / 健康云一致）：
 *
 *   nonce        = base64(12 字节随机)
 *   signedNonce  = base64(ssecurity[0..4] + nonce + sha256(ssecurity + nonce))
 *   _sign        = base64(sha256(METHOD\nPATH\nQUERY_OR_BODY\nsignedNonce))
 *
 * ssecurity 是登录返回的 base64 串，前 4 字节作为前缀直接拼进去。
 */
object MiSign {

    fun signedNonce(ssecurityB64: String): String {
        val ssecurity = decodeB64(ssecurityB64) ?: ByteArray(0)
        val nonce = ByteArray(12).also { Random.nextBytes(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(ssecurity + nonce)
        return encodeB64(ssecurity.copyOfRange(0, minOf(4, ssecurity.size)) + nonce + hash)
    }

    fun sign(method: String, path: String, queryOrBody: String, signedNonce: String): String {
        val text = "$method\n$path\n$queryOrBody\n$signedNonce"
        return encodeB64(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }

    fun md5Upper(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.uppercase()

    fun encodeB64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    fun decodeB64(s: String): ByteArray? = runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()

    /** 设备授权：HMAC-SHA256(authkey, nonce)，与 AstroBox 的 miwear-auth 一致 */
    fun authHmac(authkeyHex: String, nonce: ByteArray): ByteArray {
        val key = FrameCodec.unhex(authkeyHex) ?: throw IllegalArgumentException("authkey 不是合法十六进制")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(nonce)
    }
}
