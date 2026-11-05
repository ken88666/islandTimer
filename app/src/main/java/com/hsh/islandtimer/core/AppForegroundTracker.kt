package com.hsh.islandtimer.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hsh.islandtimer.service.UsageTimerService

/**
 * 前台应用状态机。
 *
 * 核心语义（用户需求）：
 * - 上午打开抖音：岛从 0 开始走；
 * - 退到桌面：结算本次会话，岛消失；
 * - 下午再打开：岛从上午累计的时长继续走。
 *
 * 关键区分两个量：
 * - todayAccumulatedMs：今日已累计（持久化）；
 * - sessionStartRealtime：本次会话起点（内存态，elapsedRealtime）。
 * 展示值 = todayAccumulatedMs + (elapsedRealtime - sessionStartRealtime)。
 *
 * 防闪岛 / 防误隐藏：
 * - app 启动动画/闪屏会夹杂瞬态「桌面/非被计时应用」窗口事件；
 * - hide 统一走防抖（500ms），且 show 后 2 秒宽限期内进一步延后，
 *   期间被计时应用回到前台则取消隐藏。
 */
object AppForegroundTracker {
    private const val HIDE_DEBOUNCE_MS = 500L
    private const val SHOW_GRACE_MS = 2000L
    private const val EVENT_LOG_MAX = 40

    private var appContext: Context? = null

    private var currentPkg: String? = null
    private var sessionStartRealtime = 0L
    private var todayAccumulatedMs = 0L

    /** 息屏标记：屏灭后停止一切计时，解锁后才恢复 */
    @Volatile private var screenOff = false

    /** 最近一次确认被计时应用在前台的时间（elapsedRealtime），用于宽限期 */
    private var lastTrackedRealtime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { settleAndHide(verify = true) }

    private val eventLog = ArrayDeque<String>()

    /** hide 前核实前台的逻辑（由无障碍服务注入，返回 true = 仍在前台，不应隐藏） */
    var foregroundVerifier: (() -> Boolean)? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    fun currentPackage(): String? = currentPkg

    fun isScreenOff(): Boolean = screenOff

    fun displayMs(): Long {
        currentPkg ?: return 0L
        return todayAccumulatedMs + (SystemClock.elapsedRealtime() - sessionStartRealtime)
    }

    /** 当前会话自开始以来的实时增量（UI 汇总用，未结算部分） */
    fun liveSessionMs(): Long =
        if (currentPkg != null) SystemClock.elapsedRealtime() - sessionStartRealtime else 0L

    fun isLauncher(pkg: String): Boolean =
        pkg == "com.miui.home" || pkg.contains("launcher", ignoreCase = true)

    /** 最近的前台事件日志（无 adb 排障用） */
    fun recentEvents(): String = eventLog.joinToString("\n")

    private fun logEvent(msg: String) {
        val t = System.currentTimeMillis() % 100_000
        eventLog.addLast("[$t] $msg")
        while (eventLog.size > EVENT_LOG_MAX) eventLog.removeFirst()
    }

    fun onForegroundChanged(
        pkg: String,
        isLauncher: Boolean,
        now: Long = SystemClock.elapsedRealtime()
    ) {
        val ctx = appContext ?: return

        // 息屏期间不接受任何前台事件（onScreenOff 已结算）
        if (screenOff) return

        // 桌面 / 非被计时应用：延迟确认后隐藏（防抖 + 宽限期）
        if (isLauncher || !TrackedApps.isTracked(pkg)) {
            logEvent("hide-sched $pkg")
            scheduleHide(now)
            return
        }

        // 被计时应用进入前台
        handler.removeCallbacks(hideRunnable)
        lastTrackedRealtime = now
        if (currentPkg != pkg) {
            // 结算上一个被计时应用
            currentPkg?.let { prev ->
                TimeStore.setTodayMs(prev, todayAccumulatedMs + (now - sessionStartRealtime))
            }
            currentPkg = pkg
            sessionStartRealtime = now
            todayAccumulatedMs = TimeStore.getTodayMs(pkg)
            logEvent("show $pkg")
            UsageTimerService.show(ctx)
        } else {
            logEvent("keep $pkg")
        }
    }

    private fun scheduleHide(now: Long) {
        handler.removeCallbacks(hideRunnable)
        // 最早可隐藏时间 = max(本次防抖, show 后宽限期)
        val fireAt = maxOf(now + HIDE_DEBOUNCE_MS, lastTrackedRealtime + SHOW_GRACE_MS)
        handler.postDelayed(hideRunnable, (fireAt - now).coerceAtLeast(0L))
    }

    private fun settleAndHide(verify: Boolean) {
        val ctx = appContext ?: return
        // 防抖隐藏才核实前台；息屏隐藏必须无条件执行（息屏瞬间 getRootInActiveWindow 仍是原应用）
        if (verify && foregroundVerifier?.invoke() == true) {
            logEvent("cancel-hide (foreground)")
            return
        }
        val now = SystemClock.elapsedRealtime()
        currentPkg?.let { prev ->
            TimeStore.setTodayMs(prev, todayAccumulatedMs + (now - sessionStartRealtime))
        }
        currentPkg?.let { logEvent("hide $it") }
        currentPkg = null
        UsageTimerService.hide(ctx)
    }

    /** 息屏：立即结算撤岛（不走防抖），之后拒绝前台事件 */
    fun onScreenOff() {
        if (screenOff) return
        screenOff = true
        handler.removeCallbacks(hideRunnable)
        settleAndHide(verify = false) // 息屏无条件隐藏，不做前台核实
    }

    /** 解锁亮屏：若被计时应用仍在前台，从累计时长续计 */
    fun onUserPresent(resumePkg: String?) {
        if (!screenOff) return
        screenOff = false
        if (resumePkg == null) return
        val ctx = appContext ?: return
        if (resumePkg == ctx.packageName) return
        if (isLauncher(resumePkg) || !TrackedApps.isTracked(resumePkg)) return
        currentPkg = resumePkg
        sessionStartRealtime = SystemClock.elapsedRealtime()
        todayAccumulatedMs = TimeStore.getTodayMs(resumePkg)
        lastTrackedRealtime = SystemClock.elapsedRealtime()
        logEvent("resume $resumePkg")
        UsageTimerService.show(ctx)
    }
}
