package com.kldxst.studio.wear

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AuthKeys(
    val decKey: ByteArray,
    val encKey: ByteArray,
    val decNonce: ByteArray,
    val encNonce: ByteArray
)

/**
 * MiWear 认证与载荷加密。
 *
 * KDF/字段顺序来自 AstroBox-NG `components/auth.rs`：
 *   hmac_key = HMAC-SHA256(phone_nonce || watch_nonce, authkey)
 *   okm = HMAC-SHA256(hmac_key, prev || "miwear-auth" || counter(1..3))
 * 取 okm[0..16]=dec_key, [16..32]=enc_key, [32..36]=dec_nonce, [36..40]=enc_nonce。
 */
object MiWearCrypto {
    private const val TAG = "miwear-auth"

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    fun kdf(authkey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): AuthKeys {
        require(authkey.size == 16) { "authkey 必须是 16 字节" }
        require(phoneNonce.size == 16 && watchNonce.size == 16) { "双方 nonce 必须是 16 字节" }
        val initKey = phoneNonce + watchNonce
        val hmacKey = hmacSha256(initKey, authkey)
        val okm = ByteArray(64)
        var prev = ByteArray(0)
        var offset = 0
        for (counter in 1..3) {
            prev = hmacSha256(hmacKey, prev, TAG.toByteArray(Charsets.US_ASCII), byteArrayOf(counter.toByte()))
            val take = minOf(32, 64 - offset)
            prev.copyInto(okm, offset, 0, take)
            offset += take
        }
        return AuthKeys(
            decKey = okm.copyOfRange(0, 16),
            encKey = okm.copyOfRange(16, 32),
            decNonce = okm.copyOfRange(32, 36),
            encNonce = okm.copyOfRange(36, 40)
        )
    }

    fun aes128Ctr(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 密钥必须是 16 字节" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        // AstroBox 的 aes128_ctr_crypt(key, iv=key, data) 就是这个形态。
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return cipher.doFinal(data)
    }

    /** AES-128-CCM，tag 32 bit；BC lightweight API 不依赖设备厂商 JCE。 */
    fun aes128Ccm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 密钥必须是 16 字节" }
        require(nonce.size == 12) { "CCM nonce 必须是 12 字节" }
        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(
            true,
            AEADParameters(KeyParameter(key), 32, nonce, aad)
        )
        val out = ByteArray(ccm.getOutputSize(plaintext.size))
        val n = ccm.processBytes(plaintext, 0, plaintext.size, out, 0)
        ccm.doFinal(out, n)
        return out
    }

    fun aes128CcmDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 密钥必须是 16 字节" }
        require(nonce.size == 12) { "CCM nonce 必须是 12 字节" }
        val ccm = CCMBlockCipher.newInstance(AESEngine.newInstance())
        ccm.init(false, AEADParameters(KeyParameter(key), 32, nonce, aad))
        val out = ByteArray(ccm.getOutputSize(ciphertext.size))
        val n = ccm.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        ccm.doFinal(out, n)
        return out
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim().replace(" ", "")
        if (clean.length != 32 || !clean.matches(Regex("[0-9a-fA-F]{32}"))) return null
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
