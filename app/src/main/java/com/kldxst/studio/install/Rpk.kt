package com.kldxst.studio.install

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 资源包（.rpk / .bin / .face）的离线体检。
 *
 * 安装前必须先把这三件事做完再谈「推给手环」：
 *   1. 是不是合法 zip、条目有没有路径穿越（`../`）
 *   2. manifest.json 能不能解析、包名/版本/机型是否对得上
 *   3. SHA-256 是多少 —— 服务端清单里带哈希，装之前比对，防止被中间人换包
 */
object Rpk {

    data class Info(
        val kind: String,
        val packageName: String?,
        val name: String?,
        val versionName: String?,
        val versionCode: Int?,
        val minPlatformVersion: Int?,
        val deviceTypes: List<String>,
        val features: List<String>,
        val sha256: String,
        val size: Long,
        val entries: Int,
        val error: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("kind", kind)
            put("package", packageName ?: JSONObject.NULL)
            put("name", name ?: JSONObject.NULL)
            put("versionName", versionName ?: JSONObject.NULL)
            put("versionCode", versionCode ?: JSONObject.NULL)
            put("minPlatformVersion", minPlatformVersion ?: JSONObject.NULL)
            put("deviceTypes", JSONObject.wrap(deviceTypes))
            put("features", JSONObject.wrap(features))
            put("sha256", sha256)
            put("size", size)
            put("entries", entries)
            put("error", error ?: JSONObject.NULL)
        }
    }

    fun kindOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "rpk" -> "quickapp"
        "face" -> "watchface"
        "bin" -> "firmware"
        else -> "unknown"
    }

    fun inspect(file: File): Info {
        val kind = kindOf(file.name)
        val digest = MessageDigest.getInstance("SHA-256")
        var manifestText: String? = null
        var entries = 0
        var unsafe: String? = null
        var declaredBytes = 0L

        // 第一遍只算哈希（大包不必整包进内存）
        runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
        }

        try {
            // ZipFile 只读中央目录，不会像 ZipInputStream 那样为了跳过条目解压整包。
            ZipFile(file).use { zip ->
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val e = all.nextElement()
                    entries++
                    if (entries > MAX_ENTRIES) {
                        unsafe = "资源包条目过多，已拒绝"
                        break
                    }
                    val name = e.name ?: ""
                    val normalized = name.replace('\\', '/')
                    if (normalized.startsWith("/") ||
                        Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
                        normalized.split('/').any { it == ".." } ||
                        normalized.contains('\u0000')
                    ) {
                        unsafe = "资源包含非法路径条目，已拒绝"
                        break
                    }
                    if (e.size > 0) declaredBytes += e.size
                    if (declaredBytes > MAX_UNCOMPRESSED_BYTES) {
                        unsafe = "资源包解压后超过 512MB 上限"
                        break
                    }
                    if (manifestText == null && !e.isDirectory &&
                        (normalized == "manifest.json" || normalized.endsWith("/manifest.json"))
                    ) {
                        zip.getInputStream(e).use { manifestText = readLimited(it, 512 * 1024) }
                    }
                }
            }
        } catch (e: Exception) {
            return Info(kind, null, null, null, null, null, emptyList(), emptyList(),
                hex(digest.digest()), file.length(), entries, "不是合法的资源包：${e.message}")
        }

        if (unsafe != null) {
            return Info(kind, null, null, null, null, null, emptyList(), emptyList(),
                hex(digest.digest()), file.length(), entries, unsafe)
        }
        val text = manifestText
            ?: return Info(kind, null, null, null, null, null, emptyList(), emptyList(),
                hex(digest.digest()), file.length(), entries, "缺少 manifest.json")

        return try {
            val o = JSONObject(text)
            val features = ArrayList<String>()
            o.optJSONArray("features")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.optString("name")?.let { features.add(it) }
            }
            val devices = ArrayList<String>()
            o.optJSONArray("deviceTypeList")?.let { a ->
                for (i in 0 until a.length()) devices.add(a.optString(i))
            }
            Info(
                kind = kind,
                packageName = o.optString("package").takeIf { it.isNotBlank() },
                name = o.optString("name").takeIf { it.isNotBlank() },
                versionName = o.optString("versionName").takeIf { it.isNotBlank() },
                versionCode = if (o.has("versionCode")) o.optInt("versionCode") else null,
                minPlatformVersion = if (o.has("minPlatformVersion")) o.optInt("minPlatformVersion") else null,
                deviceTypes = devices,
                features = features,
                sha256 = hex(digest.digest()),
                size = file.length(),
                entries = entries,
                error = null
            )
        } catch (e: Exception) {
            Info(kind, null, null, null, null, null, emptyList(), emptyList(),
                hex(digest.digest()), file.length(), entries, "manifest.json 解析失败")
        }
    }

    private const val MAX_ENTRIES = 20_000
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024

    private fun readLimited(input: InputStream, limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toString("UTF-8")
    }

    private fun hex(bytes: ByteArray): String {
        val t = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(t[v ushr 4]).append(t[v and 0x0F])
        }
        return sb.toString()
    }
}
