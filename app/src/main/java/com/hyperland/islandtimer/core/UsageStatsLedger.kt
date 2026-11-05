package com.hyperland.islandtimer.core

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId

/**
 * UsageStatsManager 权威账本：
 * - 补历史、对账（无障碍服务漏事件时校准）；
 * - 响应有秒级延迟，故只做账本，不做实时触发。
 */
object UsageStatsLedger {

    fun hasUsageAccess(context: Context): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24 * 3600_000L, now)
        return !stats.isNullOrEmpty()
    }

    /** 最近被调到前台的应用包名（无障碍服务连接时补一次状态） */
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    fun currentForegroundPackage(context: Context, lookbackMin: Long = 5L): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMin * 60_000L, now)
        var last: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val isFg = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                (Build.VERSION.SDK_INT >= 29 && ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
            if (isFg) last = ev.packageName
        }
        return last
    }

    /** 今日前台时长（毫秒） */
    fun getTodayForegroundMs(context: Context, pkg: String): Long =
        getDayForegroundMs(context, pkg, LocalDate.now())

    /**
     * 指定日期（自然日，系统时区）内 pkg 的前台时长。
     * - 过去的日子：以当日 24:00 为闭合上界；
     * - 今天：以当前时刻为上界（含正在进行的活动会话）。
     */
    @SuppressLint("NewApi")
    fun getDayForegroundMs(context: Context, pkg: String, date: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val isToday = now < dayEnd
        val end = if (isToday) now else dayEnd
        val openEndTs = if (isToday) now else -1L
        return sumForegroundMs(context, pkg, start, end, openEndTs)
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun sumForegroundMs(
        context: Context,
        pkg: String,
        start: Long,
        end: Long,
        openEndTs: Long
    ): Long {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, end)
        val useMove = Build.VERSION.SDK_INT >= 29
        var total = 0L
        var fgStart = -1L
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.packageName != pkg) continue
            val isStart = if (useMove) ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                          else ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            val isEnd = if (useMove) ev.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                        else ev.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            if (isStart) {
                if (fgStart < 0) fgStart = ev.timeStamp
            } else if (isEnd) {
                if (fgStart >= 0) {
                    total += ev.timeStamp - fgStart
                    fgStart = -1
                }
            }
        }
        if (fgStart >= 0) total += (if (openEndTs > 0) openEndTs else end) - fgStart
        return total
    }
}
