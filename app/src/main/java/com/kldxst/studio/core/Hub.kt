package com.kldxst.studio.core

import android.content.Context
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.bridge.BridgeEngine
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.bridge.OkHttpFetcher
import com.kldxst.studio.bridge.RingLog
import com.kldxst.studio.wear.WearLink

/**
 * 进程内唯一的服务容器。
 *
 * 控制台（WebView）只通过 [com.kldxst.studio.console.ConsoleBridge] 访问它，
 * 所以「原生能力」的边界就是这一个对象的公开成员 —— 加功能时先想清楚
 * 它属于这里，还是属于服务端下发的页面（后者不需要重新打包 APK）。
 */
object Hub {

    lateinit var app: Context
        private set

    val log = RingLog(320)

    @Volatile
    var policy: BridgePolicy = BridgePolicy()

    @Volatile
    var bridgeRunning: Boolean = false

    @Volatile
    var lastBridgeError: String? = null

    val startedAt: Long = System.currentTimeMillis()

    val wear: WearLink by lazy { WearLink(app) }

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
        log.add("策略已更新：白名单=${p.allowHosts.size} 黑名单=${p.blockHosts.size} 上限=${p.maxResponseBytes}B")
    }

    /** 给控制台的能力声明：页面据此决定显示哪些按钮，而不是靠猜 */
    fun capabilities(): Map<String, Any> = mapOf(
        "wear" to true,
        "notify" to true,
        "bridge" to true,
        "rpkInspect" to true,
        "licensePush" to true,
        "installPush" to false,
        "bleProtocol" to false
    )

    fun buildInfo(): Map<String, Any> = mapOf(
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
        "applicationId" to BuildConfig.APPLICATION_ID,
        "console" to BuildConfig.CONSOLE_URL
    )
}
