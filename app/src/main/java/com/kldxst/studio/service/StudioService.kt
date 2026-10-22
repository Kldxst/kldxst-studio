package com.kldxst.studio.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.MainActivity
import com.kldxst.studio.R
import com.kldxst.studio.StudioApp
import com.kldxst.studio.core.Hub
import kotlinx.coroutines.launch

/**
 * 前台服务：网桥 + 设备保持。
 *
 * 为什么必须是前台服务：快应用的请求随时可能到达（激活、云存档、排行榜），
 * 后台进程被系统回收就等于「网桥掉线」。前台服务把进程钉住，锁屏也能代发。
 */
class StudioService : LifecycleService() {

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Hub.wear.onMessage = { text -> onWearFrame(text) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startBridge()
        }
        return START_STICKY
    }

    private fun startBridge() {
        startForeground(NOTIFICATION_ID, buildNotification("正在连接手环…"))
        Hub.bridgeRunning = true
        Hub.log.add("服务启动")
        lifecycleScope.launch {
            try {
                val node = Hub.wear.connect()
                Hub.wear.authorize()
                Hub.wear.listen()
                updateNotification("已连接 ${node.name ?: "手环"}，网桥就绪")
                Hub.log.add("已连接 ${node.name}（${node.id}），网桥就绪")
            } catch (e: Exception) {
                Hub.lastBridgeError = e.message
                updateNotification("未连接手环：${e.message}")
                Hub.log.add("连接失败：${e.message}")
            }
        }
    }

    private fun stopBridge() {
        Hub.bridgeRunning = false
        Hub.wear.disconnect()
        Hub.log.add("服务停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * 快应用上行帧 → 引擎。
     *
     * 包名固定用本应用的 applicationId：官方 SDK 的通道身份由 binder UID 决定，
     * 一个 APK 只能服务同名快应用。这一点在控制台的「能力」页有明确说明，
     * 不做含糊处理。
     */
    private fun onWearFrame(text: String) {
        val addr = Hub.wear.node?.id ?: "unknown"
        val pkg = BuildConfig.APPLICATION_ID
        Hub.bridge.onFrame(addr, pkg, text) { reply ->
            lifecycleScope.launch {
                runCatching { Hub.wear.send(reply) }
                    .onFailure { Hub.log.add("回包失败：${it.message}") }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, StudioApp.CHANNEL_SERVICE)
            .setContentTitle("K社工坊")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        Hub.bridgeRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.kldxst.studio.START"
        const val ACTION_STOP = "com.kldxst.studio.STOP"
        private const val NOTIFICATION_ID = 1101

        fun start(ctx: Context) {
            val i = Intent(ctx, StudioService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, StudioService::class.java).setAction(ACTION_STOP)
            runCatching { ctx.startService(i) }
        }
    }
}

/** 开机自启：用户上次开着网桥，就继续开着 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.LOCKED_BOOT_COMPLETED") return
        if (context.getSharedPreferences("kldxst", Context.MODE_PRIVATE).getBoolean("bridge.autoStart", false)) {
            StudioService.start(context)
        }
    }
}
