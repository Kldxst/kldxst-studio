package com.kldxst.studio.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kldxst.studio.core.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知转发：手机通知 → 手表。
 *
 * 真实协议是 Notification.NotifyData protobuf，不是 JSON；每条通知需要稳定的
 * (uid/app_id/app_group/key) 身份，删除时用同一身份。这里直接调用 WearController。
 */
class NotifyRelay : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Hub.wearController.connected || !Hub.wearController.authenticated) return
        if (!prefs().getBoolean("notify.relay", true)) return

        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        if (prefs().getBoolean("notify.ignoreOngoing", true) &&
            (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        ) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(64) ?: return
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(400) ?: ""
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val key = notificationKey(sbn)

        scope.launch {
            runCatching {
                Hub.wearController.notify(
                    title = title,
                    body = body,
                    appName = appName,
                    appId = pkg,
                    appGroup = "phone",
                    key = key
                )
            }.onFailure { Hub.log.add("通知转发失败：${it.message}") }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!Hub.wearController.connected || !Hub.wearController.authenticated) return
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        scope.launch {
            runCatching {
                Hub.wearController.removeNotification(notificationKey(sbn), pkg, "phone")
            }.onFailure { Hub.log.add("通知撤回失败：${it.message}") }
        }
    }

    private fun notificationKey(sbn: StatusBarNotification): String =
        "phone:${sbn.packageName}:${sbn.id}:${sbn.tag ?: ""}"

    private fun prefs() = applicationContext.getSharedPreferences("kldxst", MODE_PRIVATE)
}
