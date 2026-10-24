package com.kldxst.studio.core

import android.content.Context
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.bridge.BridgeEngine
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bridge.OkHttpFetcher
import com.kldxst.studio.bridge.RingLog
import com.kldxst.studio.account.MiAccount
import com.kldxst.studio.account.MiLogin
import com.kldxst.studio.bt.MiWear
import com.kldxst.studio.bt.ProtocolProfile
import com.kldxst.studio.secure.Vault
import org.json.JSONObject

/**
 * 进程内唯一的服务容器。
 *
 * 只有直连一条链路（[MiWear]），不再有「小米运动健康代转」的旁路。
 * 控制台通过 [com.kldxst.studio.console.ConsoleBridge] 访问这里。
 */
object Hub {

    lateinit var app: Context
        private set

    val log = RingLog(320)

    @Volatile
    var policy: BridgePolicy = BridgePolicy()

    @Volatile
    var protocol: ProtocolProfile = ProtocolProfile()

    @Volatile
    var bridgeRunning: Boolean = false

    @Volatile
    var lastError: String? = null

    val startedAt: Long = System.currentTimeMillis()

    val wear: MiWear by lazy { MiWear(app) { line -> log.add("[链路] $line") } }

    /** 小米账号：登录后拿 ssecurity / serviceToken，再取设备 authkey */
    val account: MiLogin by lazy { MiLogin { line -> log.add("[账号] $line") } }

    @Volatile
    var cred: MiAccount.Credentials? = null

    private val fetcher: OkHttpFetcher by lazy { OkHttpFetcher { policy } }

    val bridge: BridgeEngine get() = bridgeDelegate.value

    @Volatile
    private var initialized = false

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            app = ctx.applicationContext
            restoreState()
            initialized = true
        }
    }

    /**
     * 控制台进程重启后仍要能看到已登录状态、上次校准的协议和网桥策略。
     * 这些值都在 [Vault] 里，不能只写入不读取。
     */
    private fun restoreState() {
        runCatching {
            Vault.get(KEY_CRED)?.let { text ->
                val o = JSONObject(text)
                val userId = o.optString("userId")
                if (userId.isNotBlank()) {
                    cred = MiAccount.Credentials(
                        userId = userId,
                        cUserId = o.optString("cUserId"),
                        ssecurity = o.optString("ssecurity"),
                        serviceToken = o.optString("serviceToken"),
                        passToken = o.optString("passToken"),
                        deviceId = o.optString("deviceId")
                    )
                }
            }
        }.onFailure { log.add("账号状态恢复失败：${it.message}") }

        runCatching {
            Vault.get(KEY_PROTOCOL)?.let { text ->
                val p = ProtocolProfile.fromJson(JSONObject(text))
                protocol = p
                wear.profile = p
            }
        }.onFailure { log.add("协议参数恢复失败：${it.message}") }

        runCatching {
            Vault.get(KEY_POLICY)?.let { text ->
                val p = BridgePolicy.fromJson(JSONObject(text))
                policy = p
                if (bridgeInitialized) bridge.updatePolicy(p)
            }
        }.onFailure { log.add("网桥策略恢复失败：${it.message}") }
    }

    private val bridgeInitialized: Boolean
        get() = bridgeDelegate.isInitialized()

    private val bridgeDelegate = lazy {
        BridgeEngine(fetcher, { policy }, { line -> log.add(line) })
    }

    fun applyPolicy(p: BridgePolicy) {
        policy = p
        bridge.updatePolicy(p)
        log.add("网桥策略已更新：白名单 ${p.allowHosts.size} 条")
    }

    fun applyProtocol(p: ProtocolProfile) {
        protocol = p
        wear.profile = p
        log.add("协议参数已更新：magic=0x${p.magic.toString(16)} 命令 ${p.ops.size} 条")
    }

    /** 能力声明：页面据此决定显示哪些按钮，而不是靠猜 */
    fun capabilities(): Map<String, Any> = mapOf(
        "direct" to true,
        "ble" to true,
        "spp" to true,
        "bridge" to true,
        "rpkInspect" to true,
        "notifyRelay" to true,
        "protocolCalibrated" to (protocol.ops.isNotEmpty())
    )

    fun buildInfo(): Map<String, Any> = mapOf(
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
        "applicationId" to BuildConfig.APPLICATION_ID,
        "console" to BuildConfig.CONSOLE_URL
    )

    const val KEY_CRED = "mi.cred"
    const val KEY_PROTOCOL = "protocol.profile"
    const val KEY_POLICY = "bridge.policy"
}
