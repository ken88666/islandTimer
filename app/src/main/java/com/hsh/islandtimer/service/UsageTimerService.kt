package com.hsh.islandtimer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hsh.islandtimer.R
import com.hsh.islandtimer.core.AppForegroundTracker
import com.hsh.islandtimer.island.IslandNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 计时前台服务：只负责保活 + 心跳；岛通知改为 notify() 通道。
 *
 * 关键改动：HyperOS 3 上「前台服务通知（startForeground）」会被挤下岛/不稳定，
 * 而 notify() 发的 promoted 通知能稳定上岛。所以这里：
 * - startForeground() 用一条最小化、不进岛的服务通知（ID=1002，IMPORTANCE_MIN）；
 * - 真正的岛通知（ID=1001）走 IslandNotifier.showIsland() -> notify()；
 * - tickLoop() 每秒用 notify() 刷新岛文案，时长精确到秒且持续保持上岛。
 */
class UsageTimerService : Service() {

    companion object {
        private const val EXTRA_SHOW = "show"
        private const val SERVICE_CHANNEL_ID = "timer_service"
        private const val SERVICE_NOTIFICATION_ID = 1002
        private const val TICK_MS = 1_000L

        fun show(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UsageTimerService::class.java).putExtra(EXTRA_SHOW, true)
            )
        }

        fun hide(context: Context) {
            try {
                context.startService(
                    Intent(context, UsageTimerService::class.java).putExtra(EXTRA_SHOW, false)
                )
            } catch (e: IllegalStateException) {
                // 后台启动受限时，通知本就不存在，忽略即可
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var visible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        IslandNotifier.ensureChannel(this)
        ensureServiceChannel()
        scope.launch { tickLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val show = intent?.getBooleanExtra(EXTRA_SHOW, false) ?: false
        visible = show
        if (show) {
            // 前台服务用最小化通知（不进岛）
            startForegroundWithType(buildServiceNotification())
            // 岛通知走 notify()，稳定上岛
            IslandNotifier.showIsland(
                this,
                AppForegroundTracker.currentPackage() ?: "",
                AppForegroundTracker.displayMs()
            )
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            IslandNotifier.hideIsland(this)
        }
        return START_STICKY
    }

    private suspend fun tickLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            if (!visible) continue
            val pkg = AppForegroundTracker.currentPackage() ?: continue
            IslandNotifier.updateIsland(this, pkg, AppForegroundTracker.displayMs())
        }
    }

    private fun ensureServiceChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "计时服务",
            // IMPORTANCE_MIN 会让 HyperOS 视作“不可见”，进而冻结/杀掉后台进程；
            // 用 DEFAULT 保持一条可见的常驻通知，进程才能在后台持续活跃。
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "后台计时保活服务"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 最小化的前台服务通知：不进岛，只在通知栏静默存在 */
    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("计时服务运行中")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    @SuppressLint("NewApi")
    private fun startForegroundWithType(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // 后台启动前台服务被拒时降级为普通通知
            getSystemService(NotificationManager::class.java)
                .notify(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
