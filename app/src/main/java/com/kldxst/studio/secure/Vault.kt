package com.kldxst.studio.secure

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地机密存储：authkey、许可证、控制台会话令牌。
 *
 * 用 Android Keystore 里的 AES-256-GCM 密钥加密后再落 SharedPreferences ——
 * 密钥由 TEE/StrongBox 持有，**不可导出**，即使 /data 被整体拷走也解不开。
 * 这比 EncryptedSharedPreferences 少一个已弃用的依赖，行为也更可控。
 */
object Vault {

    private const val ALIAS = "kldxst_vault_v1"
    private const val PREFS = "kldxst_vault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        synchronized(this) {
            if (::prefs.isInitialized) return
            prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // 首次触发生成密钥，避免第一次写入时才失败
            runCatching { secretKey() }
        }
    }

    fun get(key: String): String? {
        check(::prefs.isInitialized) { "Vault 尚未初始化" }
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val parts = stored.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(key: String, value: String) {
        check(::prefs.isInitialized) { "Vault 尚未初始化" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(key, packed).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun keys(): Set<String> = prefs.all.keys

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
