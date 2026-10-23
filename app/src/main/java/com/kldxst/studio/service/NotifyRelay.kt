package com.kldxst.studio.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kldxst.studio.bt.FrameCodec
import com.kldxst.studio.core.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 通知转发：手机通知 → 手环。
 *
 * 需要在系统设置里授予「通知使用权」，没授予时本服务不会被启动。
 * 只在已连接且配置了 notify 消息号时才发，避免无效帧污染通道。
 */
class NotifyRelay : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Hub.wear.connected) return
        if (Hub.protocol.ops["notify"] == null) return
        if (!prefs().getBoolean("notify.relay", true)) return

        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        if (prefs().getBoolean("notify.ignoreOngoing", true) &&
            (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        ) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(64) ?: return
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(200) ?: ""

        val payload = JSONObject()
            .put("app", pkg)
            .put("title", title)
            .put("body", body)
            .put("at", sbn.postTime)

        scope.launch {
            runCatching { Hub.wear.command("notify", FrameCodec.utf8(payload.toString()), 4000) }
                .onFailure { Hub.log.add("通知转发失败：${it.message}") }
        }
    }

    private fun prefs() = applicationContext.getSharedPreferences("kldxst", MODE_PRIVATE)
}
