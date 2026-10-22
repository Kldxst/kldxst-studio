package com.kldxst.studio.wear

import android.content.Context
import android.content.pm.PackageManager
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 手环连接层（官方 XMS Wearable 通道）。
 *
 * 传输由**小米运动健康**代管：本应用不碰 BLE，只把消息交给它转发。
 * 因此「扫描」在这里等价于「枚举已配对设备」——自己实现 BLE 得还原小米整套
 * 私有协议栈（AzkiDeck 用 2.2MB 的 native 库干这件事），不是一个量级的工程量。
 *
 * 三个前提缺一不可，界面必须能说清缺哪一个：
 *   1. 装了小米运动健康且手环已配对
 *   2. 快应用的包名与本应用一致（interconnect 的身份规则）
 *   3. 两者签名一致
 * 第 3 条不满足时 SDK 抛 SignatureVerifyFailedException，这里翻成人话。
 */
class WearLink(private val ctx: Context) {

    private val nodeApi by lazy { Wearable.getNodeApi(ctx) }
    private val authApi by lazy { Wearable.getAuthApi(ctx) }
    private val messageApi by lazy { Wearable.getMessageApi(ctx) }
    private val notifyApi by lazy { Wearable.getNotifyApi(ctx) }

    @Volatile
    var node: Node? = null
        private set

    private var listener: OnMessageReceivedListener? = null

    /** 收到快应用上行帧时回调（已在 SDK 线程，调用方自行切线程） */
    @Volatile
    var onMessage: ((String) -> Unit)? = null

    val connected: Boolean get() = node != null

    /** 宿主 App 是否可见（用于诊断页告诉用户缺哪个 App） */
    fun hosts(): Map<String, Boolean> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, Boolean>()
        for (p in HOSTS) {
            out[p] = try {
                pm.getPackageInfo(p, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
        return out
    }

    suspend fun nodes(): List<Node> {
        val raw = awaitAny(nodeApi.connectedNodes, "无法获取设备列表，请确认已安装并登录小米运动健康")
        @Suppress("UNCHECKED_CAST")
        return (raw as? List<Node>) ?: emptyList()
    }

    suspend fun connect(): Node {
        val list = nodes()
        val first = list.firstOrNull()
            ?: throw LinkException("未找到已连接的设备，请先在小米运动健康里连接手环")
        node = first
        return first
    }

    fun disconnect() {
        node?.let { n ->
            runCatching { messageApi.removeListener(n.id) }
        }
        node = null
        listener = null
    }

    /** 申请设备管理授权；已授权直接返回 */
    suspend fun authorize(): Boolean {
        val id = requireNode()
        val perms = arrayOf(Permission.DEVICE_MANAGER)
        val checked = awaitAny(authApi.checkPermissions(id, perms), "无法查询授权状态")
        if (truthy(checked)) return true
        awaitAny(authApi.requestPermission(id, perms), "授权未通过，请在弹窗中允许")
        return true
    }

    suspend fun isInstalled(): Boolean {
        val id = requireNode()
        val r = awaitAny(nodeApi.isWearAppInstalled(id), "无法查询手环上的应用列表")
        return r as? Boolean ?: false
    }

    suspend fun launch(path: String) {
        val id = requireNode()
        awaitAny(nodeApi.launchWearApp(id, path), "无法在手环上打开，请确认已安装")
    }

    suspend fun listen() {
        val id = requireNode()
        if (listener != null) return
        val l = OnMessageReceivedListener { _, bytes ->
            val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return@OnMessageReceivedListener
            onMessage?.invoke(text)
        }
        try {
            awaitAny(messageApi.addListener(id, l), "无法监听手环消息")
        } catch (e: LinkException) {
            val dup = e.cause?.message?.contains("register", ignoreCase = true) == true
            if (!dup) throw e
        }
        listener = l
    }

    suspend fun send(text: String) {
        val id = requireNode()
        awaitAny(messageApi.sendMessage(id, text.toByteArray(Charsets.UTF_8)), "消息发送失败，请确认手环仍在连接")
    }

    suspend fun notify(title: String, message: String) {
        val id = requireNode()
        awaitAny(notifyApi.sendNotify(id, title, message), "通知发送失败")
    }

    /** 设备状态：连接/充电/佩戴/睡眠/电量 */
    suspend fun watchState(): Map<String, Any?> {
        val id = node?.id ?: return emptyMap()
        val r = awaitAny(nodeApi.query(id, DataItem.ITEM_BATTERY), "无法读取设备状态") ?: return emptyMap()
        return mapOf(
            "connected" to readBool(r, "isConnected"),
            "charging" to readBool(r, "isCharging"),
            "wearing" to readBool(r, "isWearing"),
            "sleeping" to readBool(r, "isSleeping"),
            "battery" to readInt(r, "battery")
        )
    }

    private fun requireNode(): String = node?.id ?: throw LinkException("设备未连接")

    /**
     * SDK 的 Task 是 Java 泛型，但接口返回的是 raw type，
     * 统一按 Any 收再在调用处转型，避免每个调用点写一遍强转。
     */
    private suspend fun awaitAny(task: Task<*>, failMsg: String): Any? =
        suspendCancellableCoroutine { cont ->
            @Suppress("UNCHECKED_CAST")
            val t = task as Task<Any>
            try {
                t.addOnSuccessListener { v -> if (cont.isActive) cont.resume(v) }
                t.addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(LinkException(translate(e, failMsg), e))
                }
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(LinkException(failMsg, e))
            }
        }

    private fun truthy(v: Any?): Boolean = when (v) {
        is BooleanArray -> v.isNotEmpty() && v[0]
        is Array<*> -> v.isNotEmpty() && v[0] == true
        is Boolean -> v
        else -> false
    }

    /** DataQueryResult 的成员在不同 SDK 版本里可能是字段也可能是方法，走反射最稳 */
    private fun readBool(target: Any, name: String): Boolean? = runCatching {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
        if (m != null) return@runCatching m.invoke(target) as? Boolean
        target.javaClass.fields.firstOrNull { it.name == name }?.get(target) as? Boolean
    }.getOrNull()

    private fun readInt(target: Any, name: String): Int? = runCatching {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
        if (m != null) return@runCatching (m.invoke(target) as? Number)?.toInt()
        (target.javaClass.fields.firstOrNull { it.name == name }?.get(target) as? Number)?.toInt()
    }.getOrNull()

    private fun translate(e: Exception, fallback: String): String = when (e) {
        is SignatureVerifyFailedException ->
            "签名校验失败：手环上的快应用与本应用不是同一套签名"
        is AppNotInstalledException ->
            "手环上没有安装这个快应用"
        is DeviceDisconnectedException ->
            "手环已断开，请确认小米运动健康处于连接状态"
        is PermissionDeniedException ->
            "缺少设备管理授权，请在弹窗中允许后重试"
        else -> fallback
    }

    companion object {
        val HOSTS = listOf("com.mi.health", "com.xiaomi.wearable", "org.zxor.oronbox")
    }
}

class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)
