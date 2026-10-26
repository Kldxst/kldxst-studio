package com.kldxst.studio.wear

import com.kldxst.studio.proto.PrepareInfo
import com.kldxst.studio.proto.WatchFace as WearWatchFace
import com.kldxst.studio.proto.WearPacket

data class WatchFaceItem(
    val id: String,
    val name: String,
    val current: Boolean,
    val canRemove: Boolean,
    val versionCode: Long
)

suspend fun MiWearSession.listWatchFaces(): List<WatchFaceItem> {
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.WATCH_FACE)
        .setId(WearWatchFace.WatchFaceID.GET_INSTALLED_LIST.number)
        .build()
    val reply = request(p, timeoutMs = 12_000)
    val list = reply.watchFace?.takeIf { it.hasWatchFaceList() }?.watchFaceList
        ?: return emptyList()
    return list.listList.map {
        WatchFaceItem(
            id = it.id,
            name = it.name,
            current = it.isCurrent,
            canRemove = it.canRemove,
            versionCode = it.versionCode
        )
    }
}

suspend fun MiWearSession.setWatchFace(id: String) {
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.WATCH_FACE)
        .setId(WearWatchFace.WatchFaceID.SET_WATCH_FACE.number)
        .setWatchFace(WearWatchFace.newBuilder().setId(id))
        .build()
    sendFire(p)
}

suspend fun MiWearSession.removeWatchFace(id: String) {
    val p = WearPacket.newBuilder()
        .setType(WearPacket.Type.WATCH_FACE)
        .setId(WearWatchFace.WatchFaceID.REMOVE_WATCH_FACE.number)
        .setWatchFace(WearWatchFace.newBuilder().setId(id))
        .build()
    sendFire(p)
}

/**
 * .face 安装：WatchFace PREPARE_INSTALL（version_code 固定 65536）→ MASS type 16 → InstallResult。
 * .face 的 id 需要从资源包头解析；KdSync 先要求调用方给出 id，避免猜错覆盖现有表盘。
 */
suspend fun MiWearSession.installWatchFace(
    id: String,
    fileData: ByteArray,
    onProgress: (Float) -> Unit = {}
): MassResult {
    val prepare = PrepareInfo.newBuilder()
        .setId(id)
        .setSize(fileData.size)
        .setVersionCode(65536L)
        .build()
    val packet = WearPacket.newBuilder()
        .setType(WearPacket.Type.WATCH_FACE)
        .setId(WearWatchFace.WatchFaceID.PREPARE_INSTALL_WATCH_FACE.number)
        .setWatchFace(WearWatchFace.newBuilder().setPrepareInfo(prepare))
        .build()
    val reply = request(packet, timeoutMs = 20_000)
    val pr = reply.watchFace?.takeIf { it.hasPrepareReply() }?.prepareReply
        ?: return MassResult(-1, "设备未返回表盘准备响应")
    if (pr.prepareStatus.number != 0) {
        return MassResult(pr.prepareStatus.number, "表盘准备未就绪（status=${pr.prepareStatus.number}）")
    }
    val waiter = preparePacket(
        WearPacket.Type.WATCH_FACE.number,
        WearWatchFace.WatchFaceID.REPORT_INSTALL_RESULT.number
    )
    try {
        sendMass(fileData, 16, onProgress)
        val result = awaitPacket(waiter, 120_000)
        val r = result.watchFace?.takeIf { it.hasInstallResult() }?.installResult
            ?: return MassResult(-1, "设备未返回表盘安装结果")
        return MassResult(r.code.number, if (r.code.number == 2) "表盘安装成功" else "表盘安装失败 code=${r.code.number}")
    } catch (e: Exception) {
        cancelPacket(waiter)
        throw e
    }
}
