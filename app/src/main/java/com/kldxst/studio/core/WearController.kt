package com.kldxst.studio.core

import com.kldxst.studio.bt.MiWear
import com.kldxst.studio.wear.MassResult
import com.kldxst.studio.wear.MiWearSession
import com.kldxst.studio.wear.WatchFaceItem
import com.kldxst.studio.wear.WearApp
import com.kldxst.studio.wear.WearSnapshot
import com.kldxst.studio.wear.installWatchFace
import com.kldxst.studio.wear.listWatchFaces
import com.kldxst.studio.wear.removeWatchFace
import com.kldxst.studio.wear.setWatchFace
import com.kldxst.studio.wear.installQuickApp
import com.kldxst.studio.wear.launchApp
import com.kldxst.studio.wear.listInstalledApps
import com.kldxst.studio.wear.readSnapshot
import com.kldxst.studio.wear.removeApp
import com.kldxst.studio.wear.removePhoneNotification
import com.kldxst.studio.wear.sendPhoneNotification
import com.kldxst.studio.wear.sendWearMessage
import com.kldxst.studio.wear.syncSystemTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 设备管理门面：把真实的 L1/L2/PB 会话与 UI/服务解耦。
 * 这里不伪造“已认证”或“已连接”，状态全部来自协议回包。
 */
class WearController(
    private val link: MiWear,
    private val log: (String) -> Unit
) {
    val session = MiWearSession(link, log)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile var snapshot: WearSnapshot? = null
        private set
    @Volatile var apps: List<WearApp> = emptyList()
        private set
    @Volatile var watchFaces: List<WatchFaceItem> = emptyList()
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var busy: String? = null
        private set

    val connected: Boolean get() = link.connected
    val authenticated: Boolean get() = session.authenticated
    val authState: String get() = session.authState

    suspend fun connect(address: String, transport: String, authkey: String? = null): String {
        busy = "connect"
        try {
            if (link.connected) {
                session.close()
                link.close()
            }
            val name = if (transport.equals("ble", true)) link.connectGatt(address)
            else link.connectSpp(address)
            if (!authkey.isNullOrBlank()) {
                session.open(authkey)
                refresh()
            } else {
                session.startSession()
            }
            log("设备已连接：$name（${link.transport}）")
            return name
        } catch (e: Exception) {
            lastError = e.message
            throw e
        } finally {
            busy = null
        }
    }

    suspend fun authenticate(authkey: String) {
        busy = "auth"
        try {
            session.authenticate(authkey)
            refresh()
        } catch (e: Exception) {
            lastError = e.message
            throw e
        } finally {
            busy = null
        }
    }

    suspend fun refresh() {
        busy = "refresh"
        try {
            snapshot = session.readSnapshot()
        } finally {
            busy = null
        }
    }

    suspend fun loadApps() {
        busy = "apps"
        try {
            apps = session.listInstalledApps()
        } finally {
            busy = null
        }
    }

    suspend fun launch(app: WearApp, uri: String = "") {
        session.launchApp(app.packageName, app.fingerprint, uri)
    }

    suspend fun uninstall(app: WearApp) {
        session.removeApp(app.packageName, app.fingerprint)
        apps = apps.filterNot { it.packageName == app.packageName }
    }

    suspend fun syncTime(is24Hour: Boolean = true) {
        session.syncSystemTime(is24Hour)
    }

    suspend fun loadWatchFaces() {
        busy = "watchfaces"
        try {
            watchFaces = session.listWatchFaces()
        } finally {
            busy = null
        }
    }

    suspend fun setWatchFace(id: String) {
        session.setWatchFace(id)
    }

    suspend fun removeWatchFace(id: String) {
        session.removeWatchFace(id)
        watchFaces = watchFaces.filterNot { it.id == id }
    }

    suspend fun installWatchFace(id: String, data: ByteArray, progress: (Float) -> Unit = {}): MassResult {
        busy = "watchface_install"
        try {
            return session.installWatchFace(id, data, progress)
        } finally {
            busy = null
        }
    }

    suspend fun notify(
        title: String,
        body: String,
        appName: String = "KdSync",
        appId: String = "com.kldxst.studio",
        appGroup: String = "kdsync",
        key: String = "kdsync:${System.currentTimeMillis()}"
    ) {
        session.sendPhoneNotification(
            appName = appName, title = title, text = body,
            appId = appId, appGroup = appGroup, key = key
        )
    }

    suspend fun removeNotification(
        key: String,
        appId: String = "com.kldxst.studio",
        appGroup: String = "kdsync"
    ) {
        session.removePhoneNotification(key, appId, appGroup)
    }

    suspend fun sendWearMessage(pkg: String, fingerprint: ByteArray, content: ByteArray) {
        session.sendWearMessage(pkg, fingerprint, content)
    }

    suspend fun installQuickApp(packageName: String, data: ByteArray, progress: (Float) -> Unit = {}): MassResult {
        busy = "install"
        try {
            return session.installQuickApp(packageName, data, progress)
        } finally {
            busy = null
        }
    }

    fun disconnect() {
        session.close()
        link.close()
        snapshot = null
        apps = emptyList()
        watchFaces = emptyList()
    }

    fun statusJson(): JSONObject {
        val p = link.peer
        val s = snapshot
        return JSONObject()
            .put("available", link.available())
            .put("connected", link.connected)
            .put("transport", link.transport)
            .put("authenticated", session.authenticated)
            .put("authState", session.authState)
            .put("busy", busy ?: JSONObject.NULL)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("peer", p?.let {
                JSONObject().put("name", it.name).put("address", it.address)
            } ?: JSONObject.NULL)
            .put("bonded", JSONObject.wrap(link.bonded().map { it.name + "|" + it.address }))
            .put("snapshot", snapshotJson(s))
            .put("apps", JSONObject.wrap(apps.map { appJson(it) }))
            .put("watchFaces", JSONObject.wrap(watchFaces.map { watchJson(it) }))
    }

    companion object {
        fun snapshotJson(s: WearSnapshot?): JSONObject {
            if (s == null) return JSONObject().put("available", false)
            return JSONObject()
                .put("available", true)
                .put("model", s.model)
                .put("firmware", s.firmware)
                .put("serial", s.serial)
                .put("product", s.product)
                .put("battery", s.battery)
                .put("charging", s.charging)
                .put("storageUsed", s.storageUsed)
                .put("storageTotal", s.storageTotal)
                .put("storageFree", s.storageFree)
        }

        fun appJson(a: WearApp): JSONObject = JSONObject()
            .put("package", a.packageName)
            .put("name", a.name)
            .put("versionCode", a.versionCode)
            .put("canRemove", a.canRemove)
            .put("fingerprint", a.fingerprint.joinToString("") { "%02x".format(it) })

        fun watchJson(w: WatchFaceItem): JSONObject = JSONObject()
            .put("id", w.id)
            .put("name", w.name)
            .put("current", w.current)
            .put("canRemove", w.canRemove)
            .put("versionCode", w.versionCode)
    }
}
