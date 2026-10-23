package com.kldxst.studio.core

import android.content.Context
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.bridge.BridgeEngine
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bridge.OkHttpFetcher
import com.kldxst.studio.bridge.RingLog
import com.kldxst.studio.bt.MiWear
import com.kldxst.studio.bt.ProtocolProfile

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

    private val fetcher: OkHttpFetcher by lazy { OkHttpFetcher { policy } }

    val bridge: BridgeEngine by lazy {
        BridgeEngine(fetcher, { policy }, { line -> log.add(line) })
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
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
}
