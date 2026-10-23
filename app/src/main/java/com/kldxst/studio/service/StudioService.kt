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
import com.kldxst.studio.bt.FrameCodec
import com.kldxst.studio.core.Hub
import com.kldxst.studio.secure.Vault
import kotlinx.coroutines.launch

/**
 * 前台服务：保持直连 + 跑网桥。
 *
 * 直连是唯一链路：这里自己占蓝牙，不依赖小米运动健康。后台被回收就等于掉线，
 * 所以用前台服务把进程钉住。
 */
class StudioService : LifecycleService() {

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Hub.wear.onFrame = { frame ->
            val type = Hub.protocol.ops["bridge"]
            if (type != null && frame.type == type) {
                val addr = Hub.wear.peer?.address ?: "unknown"
                val text = runCatching { String(frame.payload, Charsets.UTF_8) }.getOrNull()
                if (text != null) {
                    Hub.bridge.onFrame(addr, BuildConfig.APPLICATION_ID, text) { reply ->
                        lifecycleScope.launch {
                            runCatching {
                                Hub.wear.sendFrame(type, FrameCodec.utf8(reply))
                            }.onFailure { Hub.log.add("网桥回包失败：${it.message}") }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startAll()
        }
        return START_STICKY
    }

    private fun startAll() {
        startForeground(NOTIFICATION_ID, buildNotification("准备连接…"))
        Hub.bridgeRunning = true
        Hub.log.add("服务启动")
        lifecycleScope.launch {
            val address = Vault.get("device.address")
            if (address.isNullOrBlank()) {
                updateNotification("未连接：先在控制台里连接一次手环")
                Hub.log.add("没有已保存的设备地址")
                return@launch
            }
            val transport = Vault.get("device.transport") ?: "spp"
            try {
                if (transport == "ble") Hub.wear.connectGatt(address) else Hub.wear.connectSpp(address)
                updateNotification("已连接 ${Hub.wear.peer?.name ?: address}")
                Hub.log.add("已连接 $address（$transport）")
                val key = Vault.get("authkey")
                if (!key.isNullOrBlank()) {
                    runCatching { Hub.wear.auth(key) }
                        .onSuccess { Hub.log.add("authkey 已发出，回包：${it.optString("reply")}") }
                        .onFailure { Hub.log.add("authkey 握手失败：${it.message}") }
                }
            } catch (e: Exception) {
                Hub.lastError = e.message
                updateNotification("连接失败：${e.message}")
                Hub.log.add("连接失败：${e.message}")
            }
        }
    }

    private fun stopAll() {
        Hub.bridgeRunning = false
        Hub.wear.close()
        Hub.log.add("服务停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
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

/** 开机自启：上次开着网桥就继续开着 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.LOCKED_BOOT_COMPLETED") return
        if (context.getSharedPreferences("kldxst", Context.MODE_PRIVATE).getBoolean("bridge.autoStart", false)) {
            StudioService.start(context)
        }
    }
}
