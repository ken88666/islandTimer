package com.hyperland.islandtimer.core

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * 按「日期|包名」分桶持久化累计时长，天然实现每日刷新；
 * getWeekMs 汇总最近 7 天实现每周刷新。
 */
object TimeStore {
    private const val PREFS = "usage_time"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(pkg: String, date: LocalDate): String = "$date|$pkg"

    fun getAccumulatedMs(pkg: String, date: LocalDate = LocalDate.now()): Long =
        prefs.getLong(key(pkg, date), 0L)

    fun setAccumulatedMs(pkg: String, ms: Long, date: LocalDate = LocalDate.now()) {
        prefs.edit().putLong(key(pkg, date), ms).apply()
    }

    fun getTodayMs(pkg: String): Long = getAccumulatedMs(pkg, LocalDate.now())

    fun setTodayMs(pkg: String, ms: Long) = setAccumulatedMs(pkg, ms, LocalDate.now())

    /** 每周刷新用：最近 7 天（含今天）累计 */
    fun getWeekMs(pkg: String): Long {
        val today = LocalDate.now()
        var total = 0L
        for (i in 0..6) total += getAccumulatedMs(pkg, today.minusDays(i.toLong()))
        return total
    }
}
