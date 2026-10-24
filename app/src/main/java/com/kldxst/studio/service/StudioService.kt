package com.kldxst.studio.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kldxst.studio.BuildConfig
import com.kldxst.studio.MainActivity
import com.kldxst.studio.R
import com.kldxst.studio.StudioApp
import com.kldxst.studio.bt.FrameCodec
import com.kldxst.studio.core.Hub
import com.kldxst.studio.secure.Vault
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：保持直连 + 跑网桥。
 *
 * 直连是唯一链路：这里自己占蓝牙，不依赖小米运动健康。后台被回收就等于掉线，
 * 所以用前台服务把进程钉住。
 */
class StudioService : LifecycleService() {

    private var loopJob: Job? = null

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
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("准备连接…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("准备连接…"))
            }
        }.onFailure {
            Hub.lastError = it.message
            Hub.log.add("前台服务启动失败：${it.message}")
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        Hub.bridgeRunning = true
        Hub.log.add("服务启动")
        loopJob?.cancel()
        loopJob = lifecycleScope.launch { connectionLoop() }
    }

    /**
     * 蓝牙断开后不能只把通知改成「连接失败」然后等用户重启应用。
     * 前台服务在，就按退避间隔重连；用户主动停止服务时 [Hub.bridgeRunning] 置 false。
     */
    private suspend fun connectionLoop() {
        var backoff = 3_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive && Hub.bridgeRunning) {
            val address = Vault.get("device.address")
            if (address.isNullOrBlank()) {
                updateNotification("未连接：先在控制台里连接一次手环")
                delay(30_000)
                continue
            }
            if (!Hub.wear.connected) {
                val transport = Vault.get("device.transport") ?: "spp"
                updateNotification("正在连接 ${address}…")
                try {
                    if (transport == "ble") Hub.wear.connectGatt(address) else Hub.wear.connectSpp(address)
                    Hub.lastError = null
                    updateNotification("已连接 ${Hub.wear.peer?.name ?: address}")
                    Hub.log.add("已连接 $address（$transport）")
                    val key = Vault.get("authkey")
                    if (!key.isNullOrBlank()) {
                        runCatching { Hub.wear.auth(key) }
                            .onSuccess { Hub.log.add("authkey ${it.optString("state")}") }
                            .onFailure { Hub.log.add("authkey 握手失败：${it.message}") }
                    }
                    backoff = 3_000
                } catch (e: Exception) {
                    Hub.lastError = e.message
                    updateNotification("连接失败，${(e.message ?: "请检查蓝牙").take(48)}")
                    Hub.log.add("连接失败：${e.message}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    continue
                }
            }
            delay(5_000)
        }
    }

    private fun stopAll() {
        Hub.bridgeRunning = false
        loopJob?.cancel()
        loopJob = null
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        Hub.bridgeRunning = false
        loopJob?.cancel()
        loopJob = null
        Hub.wear.onFrame = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.kldxst.studio.START"
        const val ACTION_STOP = "com.kldxst.studio.STOP"
        private const val NOTIFICATION_ID = 1101

        fun start(ctx: Context) {
            if (!canStartConnectedDeviceService(ctx)) {
                Hub.log.add("未启动前台服务：缺少附近设备权限")
                return
            }
            val i = Intent(ctx, StudioService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { Hub.log.add("启动前台服务失败：${it.message}") }
        }

        /** 应用启动/权限回调时调用；用户明确停止过或没有保存设备时不自动拉起。 */
        fun startIfEnabled(ctx: Context) {
            val prefs = ctx.getSharedPreferences("kldxst", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("bridge.autoStart", true)) return
            if (Vault.get("device.address").isNullOrBlank()) return
            start(ctx)
        }

        fun stop(ctx: Context) {
            Hub.bridgeRunning = false
            runCatching { ctx.stopService(Intent(ctx, StudioService::class.java)) }
        }

        private fun canStartConnectedDeviceService(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val connect = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
            return connect == PackageManager.PERMISSION_GRANTED
        }
    }
}

/** 开机自启：上次开着网桥且有已保存设备时继续重连 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        StudioService.startIfEnabled(context)
    }
}
