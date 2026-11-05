package com.hsh.islandtimer.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.hsh.islandtimer.R
import com.hsh.islandtimer.core.TrackedApps
import java.util.Locale

/**
 * 基础岛：AOSP「promoted ongoing（实时更新 / Live Updates）」通知。
 *
 * HyperOS 3/4（Android 16+）把 AOSP Live Updates 通知抬上超级岛，
 * 岛上显示 smallIcon + shortCriticalText，与签名无关、无需小米白名单。
 *
 * 正确姿势（Gemini / HyperBridge 同款）：在 NotificationCompat.Builder 上调用
 * setShortCriticalText() + setRequestPromotedOngoing()（androidx.core 1.15/1.16+）。
 * 之前用 raw extras / 反射设不到 Notification 内部字段，hasPromotableCharacteristics() 恒为 false。
 */
object IslandNotifier {
    const val CHANNEL_ID = "island_timer"
    const val NOTIFICATION_ID = 1001

    /** 调试测试通知用独立 ID，与正式计时通知（1001）共存、互不覆盖 */
    const val TEST_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "使用计时",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "在被计时的应用前台时，于超级岛显示使用时长"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildTimerNotification(context: Context, pkg: String, displayMs: Long): Notification {
        val label = TrackedApps.labelFor(context, pkg)
        val timeText = format(displayMs)
        return buildPromotedNotification(context, label, "已使用 $timeText", timeText)
    }

    /** 岛通知走 notify()（非前台服务），在 HyperOS 3 上稳定上岛 */
    fun showIsland(context: Context, pkg: String, displayMs: Long) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildTimerNotification(context, pkg, displayMs))
    }

    fun updateIsland(context: Context, pkg: String, displayMs: Long) =
        showIsland(context, pkg, displayMs)

    fun hideIsland(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** 调试用：显示常驻测试通知（独立 ID 1002，可与正式岛共存） */
    fun showTestIsland(context: Context) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)
            .notify(TEST_NOTIFICATION_ID, buildPromotedNotification(context, "超级岛测试", "常驻测试通知（调试）", "常驻通知测试"))
    }

    /** 撤销测试通知（只撤 1002，不动正式计时通知） */
    fun hideTestIsland(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(TEST_NOTIFICATION_ID)
    }

    /** 测试通知是否仍挂在通知栏 */
    fun isTestIslandShowing(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == TEST_NOTIFICATION_ID }
    } catch (e: Exception) {
        false
    }

    private fun buildPromotedNotification(
        context: Context,
        title: String,
        text: String,
        shortCriticalText: String
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShortCriticalText(shortCriticalText) // androidx.core 1.15+：岛上胶囊文字
            .setRequestPromotedOngoing(true)         // androidx.core 1.16+：申请上岛
            .build()
    }

    fun format(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%d小时%02d分", h, m)
            m > 0 -> String.format(Locale.getDefault(), "%d分%02d秒", m, s)
            else -> String.format(Locale.getDefault(), "%d秒", s)
        }
    }
}
