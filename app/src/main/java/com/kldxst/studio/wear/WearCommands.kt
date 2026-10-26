package com.kldxst.studio.wear

import com.google.protobuf.ByteString
import com.kldxst.studio.proto.AppInstaller
import com.kldxst.studio.proto.AppItem
import com.kldxst.studio.proto.BasicInfo
import com.kldxst.studio.proto.Date
import com.kldxst.studio.proto.DeviceInfo
import com.kldxst.studio.proto.DeviceStatus
import com.kldxst.studio.proto.LaunchInfo
import com.kldxst.studio.proto.MessageContent
import com.kldxst.studio.proto.Mass
import com.kldxst.studio.proto.Notification
import com.kldxst.studio.proto.NotifyData
import com.kldxst.studio.proto.NotifyId
import com.kldxst.studio.proto.PrepareRequest
import com.kldxst.studio.proto.PrepareResponse
import com.kldxst.studio.proto.StorageInfo
import com.kldxst.studio.proto.System as WearSystem
import com.kldxst.studio.proto.SystemTime
import com.kldxst.studio.proto.ThirdpartyApp
import com.kldxst.studio.proto.Time
import com.kldxst.studio.proto.Timezone
import com.kldxst.studio.proto.WearPacket
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date as JavaDate
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32

data class WearSnapshot(
    val model: String = "",
    val firmware: String = "",
    val serial: String = "",
    val product: String = "",
    val battery: Int = -1,
    val charging: Int = 0,
    val storageUsed: Long = 0,
    val storageTotal: Long = 0
) {
    val storageFree: Long get() = (storageTotal - storageUsed).coerceAtLeast(0)
}

data class WearApp(
    val packageName: String,
    val name: String,
    val versionCode: Int,
    val canRemove: Boolean,
    val fingerprint: ByteArray
)

data class MassResult(val code: Int, val message: String) {
    val success: Boolean get() = code == 0
}

suspend fun MiWearSession.readSnapshot(): WearSnapshot {
    var model = ""
    var firmware = ""
    var serial = ""
    var product = ""
    var battery = -1
    var charging = 0
    var used = 0L
    var total = 0L
    runCatching {
        val p = systemPacket(WearSystem.SystemID.GET_DEVICE_INFO.number)
        val info = request(p).system?.takeIf { it.hasDeviceInfo() }?.deviceInfo
        if (info != null) {
            model = info.model
            firmware = info.firmwareVersion
            serial = info.serialNumber
            product = info.productDevice
        }
    }.onFailure { lastError = it.message }
    runCatching {
        val p = systemPacket(WearSystem.SystemID.GET_DEVICE_STATUS.number)
        val sys = request(p).system
        val status = sys?.takeIf { it.hasDeviceStatus() }?.deviceStatus
        val b = status?.battery ?: sys?.takeIf { it.hasBatteryStatus() }?.batteryStatus
        if (b != null) {
            battery = b.capacity
            charging = b.chargeStatus.number
        }
    }.onFailure { lastError = it.message }
    runCatching {
        val p = systemPacket(WearSystem.SystemID.GET_STORAGE_INFO.number)
        val s = request(p).system?.takeIf { it.hasStorageInfo() }?.storageInfo
        if (s != null) {
            used = s.used
            total = s.total
        }
    }.onFailure { lastError = it.message }
    return WearSnapshot(model, firmware, serial, product, battery, charging, used, total)
}

private fun systemPacket(id: Int): WearPacket = WearPacket.newBuilder()
    .setType(WearPacket.Type.SYSTEM)
    .setId(id)
    .build()

suspend fun MiWearSession.listInstalledApps(): List<WearApp> {
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.THIRDPARTY_APP)
        .setId(ThirdpartyApp.ThirdpartyAppID.GET_INSTALLED_LIST.number)
        .build()
    val reply = request(p, timeoutMs = 12_000)
    val list = reply.thirdpartyApp
        ?.takeIf { it.hasAppItemList() }
        ?.appItemList
        ?: return emptyList()
    return list.listList.map {
        WearApp(
            packageName = it.packageName,
            name = it.appName.ifBlank { it.packageName },
            versionCode = it.versionCode,
            canRemove = it.canRemove,
            fingerprint = it.fingerprint.toByteArray()
        )
    }
}

suspend fun MiWearSession.launchApp(pkg: String, fingerprint: ByteArray, uri: String = "") {
    val info = BasicInfo.newBuilder()
        .setPackageName(pkg)
        .setFingerprint(ByteString.copyFrom(fingerprint))
        .build()
    val launch = LaunchInfo.newBuilder().setBasicInfo(info).setUri(uri).build()
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.THIRDPARTY_APP)
        .setId(ThirdpartyApp.ThirdpartyAppID.LAUNCH_APP.number)
        .setThirdpartyApp(ThirdpartyApp.newBuilder().setLaunchInfo(launch))
        .build()
    sendFire(p)
}

suspend fun MiWearSession.sendWearMessage(pkg: String, fingerprint: ByteArray, content: ByteArray) {
    val info = BasicInfo.newBuilder()
        .setPackageName(pkg)
        .setFingerprint(ByteString.copyFrom(fingerprint))
        .build()
    val msg = MessageContent.newBuilder()
        .setBasicInfo(info)
        .setContent(ByteString.copyFrom(content))
        .build()
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.THIRDPARTY_APP)
        .setId(ThirdpartyApp.ThirdpartyAppID.SEND_PHONE_MESSAGE.number)
        .setThirdpartyApp(ThirdpartyApp.newBuilder().setMessageContent(msg))
        .build()
    sendFire(p)
}

suspend fun MiWearSession.removeApp(pkg: String, fingerprint: ByteArray) {
    val info = BasicInfo.newBuilder()
        .setPackageName(pkg)
        .setFingerprint(ByteString.copyFrom(fingerprint))
        .build()
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.THIRDPARTY_APP)
        .setId(ThirdpartyApp.ThirdpartyAppID.REMOVE_APP.number)
        .setThirdpartyApp(ThirdpartyApp.newBuilder().setBasicInfo(info))
        .build()
    sendFire(p)
}

suspend fun MiWearSession.syncSystemTime(is24Hour: Boolean = true) {
    val now = Calendar.getInstance()
    val tz = TimeZone.getDefault()
    val date = Date.newBuilder()
        .setYear(now.get(Calendar.YEAR))
        .setMonth(now.get(Calendar.MONTH) + 1)
        .setDay(now.get(Calendar.DAY_OF_MONTH))
        .build()
    val time = Time.newBuilder()
        .setHour(now.get(Calendar.HOUR_OF_DAY))
        .setMinuter(now.get(Calendar.MINUTE))
        .setSecond(now.get(Calendar.SECOND))
        .setMillisecond(now.get(Calendar.MILLISECOND))
        .build()
    val zone = Timezone.newBuilder()
        .setOffset(tz.getOffset(now.timeInMillis) / 60_000)
        .setId(tz.id)
        .build()
    val st = SystemTime.newBuilder()
        .setDate(date).setTime(time).setTimeZone(zone)
        .setIs12Hours(!is24Hour)
        .build()
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.SYSTEM)
        .setId(WearSystem.SystemID.SET_SYSTEM_TIME.number)
        .setSystem(WearSystem.newBuilder().setSystemTime(st))
        .build()
    sendFire(p)
}

/**
 * 通知必须带 15 字符本地日期 yyyyMMdd'T'HHmmss；部分手表固件会对没有 'T' 的 date
 * 执行 strchr+1，直接崩溃。这里不做“优化”。
 */
suspend fun MiWearSession.sendPhoneNotification(
    appName: String,
    title: String,
    text: String,
    subTitle: String = "",
    key: String = "kdsync:${System.currentTimeMillis()}",
    appId: String = "com.kldxst.studio",
    appGroup: String = "kdsync"
) {
    val date = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(JavaDate())
    val uid = notificationUid("$appGroup:$key")
    var safeText = text
    var packet: WearPacket
    do {
        val data = NotifyData.newBuilder()
            .setAppId(appId)
            .setAppName(appName)
            .setTitle(title)
            .setSubTitle(subTitle)
            .setText(safeText)
            .setDate(date)
            .setUid(uid)
            .setAppGroup(appGroup)
            .setKey(key)
            .setCallNumber("")
            .setSupportReply(false)
            .setSupportOpen(false)
            .build()
        packet = WearPacket.newBuilder()
            .setType(WearPacket.Type.NOTIFICATION)
            .setId(Notification.NotificationID.ADD_NOTIFY.number)
            .setNotification(Notification.newBuilder().setData(data))
            .build()
        if (packet.serializedSize <= 16 * 1024) break
        safeText = safeText.dropLast((safeText.length / 8).coerceAtLeast(1))
    } while (safeText.isNotEmpty())
    if (packet.serializedSize > 16 * 1024) throw IllegalArgumentException("通知超过 16KiB")
    sendFire(packet)
}

suspend fun MiWearSession.removePhoneNotification(
    key: String,
    appId: String = "com.kldxst.studio",
    appGroup: String = "kdsync"
) {
    val id = NotifyId.newBuilder()
        .setUid(notificationUid("$appGroup:$key"))
        .setAppId(appId)
        .setAppGroup(appGroup)
        .setKey(key)
        .build()
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.NOTIFICATION)
        .setId(Notification.NotificationID.REMOVE_NOTIFY.number)
        .setNotification(Notification.newBuilder().setId(id))
        .build()
    sendFire(p)
}

private fun notificationUid(seed: String): Int {
    val hash = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    return (hash[0].toInt() and 0xFF) or
        ((hash[1].toInt() and 0xFF) shl 8) or
        ((hash[2].toInt() and 0xFF) shl 16) or
        ((hash[3].toInt() and 0xFF) shl 24)
}

/**
 * 快应用安装：ThirdpartyApp PREPARE_INSTALL_APP → MASS Prepare → MASS 分片 → InstallResult。
 * 源码把 version_code 固定为 114514，这里保持协议兼容。
 */
suspend fun MiWearSession.installQuickApp(
    packageName: String,
    fileData: ByteArray,
    onProgress: (Float) -> Unit = {}
): MassResult {
    val prepare = AppInstaller.Request.newBuilder()
        .setPackageName(packageName)
        .setVersionCode(114514)
        .setPackageSize(fileData.size)
        .build()
    val preparePacket = WearPacket.newBuilder()
        .setType(WearPacket.Type.THIRDPARTY_APP)
        .setId(ThirdpartyApp.ThirdpartyAppID.PREPARE_INSTALL_APP.number)
        .setThirdpartyApp(ThirdpartyApp.newBuilder().setInstallRequest(prepare))
        .build()
    val prepReply = request(preparePacket, timeoutMs = 20_000)
    val appResp = prepReply.thirdpartyApp
        ?.takeIf { it.hasInstallResponse() }
        ?.installResponse
        ?: return MassResult(-1, "设备未返回安装准备响应")
    if (appResp.prepareStatus.number != 0) {
        return MassResult(appResp.prepareStatus.number, "安装准备未就绪（status=${appResp.prepareStatus.number}）")
    }

    val waiter = preparePacket(
        WearPacket.Type.THIRDPARTY_APP.number,
        ThirdpartyApp.ThirdpartyAppID.REPORT_INSTALL_RESULT.number
    )
    try {
        sendMass(fileData, 64, onProgress)
        val result = awaitPacket(waiter, 120_000)
        val r = result.thirdpartyApp?.takeIf { it.hasInstallResult() }?.installResult
            ?: return MassResult(-1, "设备未返回安装结果")
        return MassResult(r.code.number, if (r.code.number == 0) "安装成功" else "安装失败 code=${r.code.number}")
    } catch (e: Exception) {
        cancelPacket(waiter)
        throw e
    }
}

suspend fun MiWearSession.sendMass(
    fileData: ByteArray,
    dataType: Int,
    onProgress: (Float) -> Unit = {}
) {
    val md5 = MessageDigest.getInstance("MD5").digest(fileData)
    val prepare = PrepareRequest.newBuilder()
        .setDataType(dataType)
        .setDataId(ByteString.copyFrom(md5))
        .setDataLength(fileData.size)
        .build()
    val preparePacket = WearPacket.newBuilder()
        .setType(WearPacket.Type.MASS)
        .setId(Mass.MassID.PREPARE.number)
        .setMass(Mass.newBuilder().setPrepareRequest(prepare))
        .build()
    val reply = request(preparePacket, timeoutMs = 30_000)
    val pr: PrepareResponse = reply.mass?.prepareResponse
        ?: throw IllegalStateException("MASS Prepare 无响应")
    if (pr.prepareStatus.number != 0) throw IllegalStateException("MASS 未就绪（status=${pr.prepareStatus.number}）")
    val slice = pr.expectedSliceLength
    if (slice <= 6) throw IllegalStateException("设备返回的 expected_slice_length 太小：$slice")

    val full = buildMassPayload(fileData, dataType, md5)
    val fragmentMax = slice - 6
    val total = (full.size + fragmentMax - 1) / fragmentMax
    if (total <= 0 || total > 65535) throw IllegalStateException("MASS 分片数量非法：$total")
    var sent = 0
    for (i in 0 until total) {
        val start = i * fragmentMax
        val end = minOf(start + fragmentMax, full.size)
        val fragment = full.copyOfRange(start, end)
        val body = ByteArrayOutputStream(4 + fragment.size)
        body.write(total and 0xFF); body.write((total ushr 8) and 0xFF)
        body.write((i + 1) and 0xFF); body.write(((i + 1) ushr 8) and 0xFF)
        body.write(fragment)
        sendL2(L2Codec.CHANNEL_MASS, L2Codec.OP_WRITE, body.toByteArray())
        sent += fragment.size
        onProgress(sent.toFloat() / full.size.coerceAtLeast(1))
    }
}

private fun buildMassPayload(fileData: ByteArray, dataType: Int, md5: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(1 + 1 + 16 + 4 + fileData.size + 4)
    out.write(0)
    out.write(dataType and 0xFF)
    out.write(md5)
    writeU32Le(out, fileData.size.toLong())
    out.write(fileData)
    val crc = CRC32().apply { update(out.toByteArray()) }.value
    writeU32Le(out, crc)
    return out.toByteArray()
}

private fun writeU32Le(out: ByteArrayOutputStream, value: Long) {
    out.write((value and 0xFF).toInt())
    out.write(((value ushr 8) and 0xFF).toInt())
    out.write(((value ushr 16) and 0xFF).toInt())
    out.write(((value ushr 24) and 0xFF).toInt())
}
