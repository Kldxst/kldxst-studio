package com.kldxst.studio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.kldxst.studio.bridge.BridgePolicy
import com.kldxst.studio.core.Hub
import com.kldxst.studio.secure.Vault

class StudioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Hub.init(this)
        Vault.init(this)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_SERVICE,
            "网桥与设备连接",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持与手环的连接，并代快应用发出网络请求"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        lateinit var instance: StudioApp
            private set
        const val CHANNEL_SERVICE = "studio_service"
    }
}
