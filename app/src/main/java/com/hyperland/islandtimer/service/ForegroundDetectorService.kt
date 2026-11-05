package com.hyperland.islandtimer.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.hyperland.islandtimer.core.AppForegroundTracker
import com.hyperland.islandtimer.core.TrackedApps
import com.hyperland.islandtimer.core.UsageStatsLedger

/**
 * 实时前台应用检测（触发层）+ 长期保活锚点。
 *
 * - 监听 TYPE_WINDOW_STATE_CHANGED 拿包名，亚秒级响应；
 * - 过滤系统浮层（SystemUI/控制中心/搜索框等），避免下拉通知栏、调起搜索框被误判成「切应用」；
 * - hide 前用 getRootInActiveWindow() 核实真实前台，双保险防误隐藏。
 */
class ForegroundDetectorService : AccessibilityService() {

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val flat = "${context.packageName}/${ForegroundDetectorService::class.java.name}"
            val short = "${context.packageName}/.${ForegroundDetectorService::class.java.simpleName}"
            return enabled.split(':').any { it == flat || it == short }
        }

        /** 系统浮层/非应用包：出现时不应触发「前台切换」 */
        private val SYSTEM_OVERLAYS = setOf(
            "com.android.systemui",
            "miui.systemui.plugin",
            "com.miui.systemui",
            "com.android.quicksearchbox",
            "com.miui.quicksearchbox",
            "android"
        )
    }

    /** 最近一次可见窗口的包名（含桌面；浮层/自身不记录），解锁续计用 */
    private var lastVisiblePkg: String? = null

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> AppForegroundTracker.onScreenOff()
                Intent.ACTION_USER_PRESENT -> AppForegroundTracker.onUserPresent(lastVisiblePkg)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppForegroundTracker.bind(this)
        // 注入 hide 前核实前台的逻辑
        AppForegroundTracker.foregroundVerifier = { isForegroundTrackedOrOverlay() }

        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 用户刚开启无障碍时，可能已经身处被计时应用，补一次状态
        val cur = UsageStatsLedger.currentForegroundPackage(this)
        if (cur != null && cur != packageName && TrackedApps.isTracked(cur) && !AppForegroundTracker.isLauncher(cur)) {
            lastVisiblePkg = cur
            AppForegroundTracker.onForegroundChanged(cur, false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || isSystemOverlay(pkg)) return
        lastVisiblePkg = pkg
        AppForegroundTracker.onForegroundChanged(
            pkg,
            AppForegroundTracker.isLauncher(pkg),
            SystemClock.elapsedRealtime()
        )
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            // 未注册时忽略
        }
        AppForegroundTracker.foregroundVerifier = null
        // 必须返回 false：Android 8+ 无障碍服务才能在重新开启后再次绑定
        return false
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            // 未注册时忽略
        }
        AppForegroundTracker.foregroundVerifier = null
        super.onDestroy()
    }

    private fun isSystemOverlay(pkg: String): Boolean = pkg in SYSTEM_OVERLAYS

    /** 当前真正前台是否「被计时应用 或 系统浮层」——是则不应隐藏 */
    private fun isForegroundTrackedOrOverlay(): Boolean {
        val pkg = currentForegroundPackage() ?: return true // 拿不到就保守：不隐藏
        return TrackedApps.isTracked(pkg) || isSystemOverlay(pkg)
    }

    private fun currentForegroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return root.packageName?.toString()
    }
}
