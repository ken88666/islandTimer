package com.hyperland.islandtimer.island

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 无需 adb 的上岛诊断：
 * 用公开 API 读回「样例通知」的 extras，确认 promoted 标记是否真的挂上；
 * 再反射调 API 36 的系统能力查询，判断系统是否允许本应用发 promoted 通知。
 */
object IslandDiagnostics {

    /** 用当前代码构建一条样例通知，读回它的 extras（等价于 dumpsys 里看 extras） */
    fun sampleNotificationExtras(context: Context): String {
        val n = IslandNotifier.buildTimerNotification(context, "com.example.test", 123_456L)
        val e = n.extras
        val sb = StringBuilder()
        sb.append("[样例通知 extras]\n")
        sb.append("  requestPromotedOngoing = ").append(e.getBoolean("android.requestPromotedOngoing")).append('\n')
        sb.append("  shortCriticalText      = ").append(e.getString("android.shortCriticalText")).append('\n')
        sb.append("  colorized              = ").append(e.getBoolean("android.colorized")).append('\n')
        sb.append("  color                  = 0x").append(e.getInt("android.color", 0).toString(16)).append('\n')
        sb.append("  title                  = ").append(e.getString("android.title")).append('\n')
        sb.append("  text                   = ").append(e.getString("android.text")).append('\n')
        sb.append("  ").append(promotableCharacteristics(n))
        return sb.toString()
    }

    /** Notification.hasPromotableCharacteristics() —— API 36+，反射调用 */
    private fun promotableCharacteristics(n: Notification): String {
        return try {
            val m = Notification::class.java.getMethod("hasPromotableCharacteristics")
            "hasPromotableCharacteristics = " + m.invoke(n)
        } catch (e: Exception) {
            "hasPromotableCharacteristics = 不可用(" + e.javaClass.simpleName + ")"
        }
    }

    fun channelInfo(context: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(IslandNotifier.CHANNEL_ID) ?: return "渠道未创建"
        val imp = when (ch.importance) {
            NotificationManager.IMPORTANCE_NONE -> "NONE(0)"
            NotificationManager.IMPORTANCE_MIN -> "MIN(1)"
            NotificationManager.IMPORTANCE_LOW -> "LOW(2)"
            NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT(3)"
            NotificationManager.IMPORTANCE_HIGH -> "HIGH(4)"
            NotificationManager.IMPORTANCE_MAX -> "MAX(5)"
            else -> "?(${ch.importance})"
        }
        return "渠道 importance = $imp（MIN 会被系统拒绝上岛）"
    }

    /** NotificationManager.canPostPromotedNotifications() —— API 36+，反射 */
    fun canPostPromoted(context: Context): String {
        if (Build.VERSION.SDK_INT < 36) {
            return "canPostPromotedNotifications = 不可用(API ${Build.VERSION.SDK_INT}<36)"
        }
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            val m = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            "canPostPromotedNotifications = " + m.invoke(nm)
        } catch (e: Exception) {
            "canPostPromotedNotifications = 反射失败(${e.message})"
        }
    }

    /** 当前活跃通知里有没有我们的计时通知 */
    fun activeNotifications(context: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        val list = nm.activeNotifications.orEmpty()
        if (list.isEmpty()) return "活跃通知：0 条"
        val ours = list.filter {
            it.id == IslandNotifier.NOTIFICATION_ID
        }
        if (ours.isEmpty()) {
            return "活跃通知 ${list.size} 条（被计时应用不在前台时属正常）"
        }
        val sb = StringBuilder("活跃通知：\n")
        for (sbn in ours) {
            val e = sbn.notification.extras
            sb.append("  ID=").append(sbn.id)
                .append(" ongoing=").append(sbn.isOngoing)
                .append(" promoted=").append(e.getBoolean("android.requestPromotedOngoing"))
                .append(" short=\"").append(e.getString("android.shortCriticalText")).append("\"\n")
        }
        return sb.toString()
    }
}
