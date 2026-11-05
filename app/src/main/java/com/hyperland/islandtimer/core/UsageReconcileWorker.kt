package com.hyperland.islandtimer.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * UsageStats 权威账本对账（周期 15 分钟 + 手动触发）。
 *
 * 无障碍事件可能因进程被杀 / 系统回收而漏记，本 Worker 把 UsageStats 的
 * 每日前台时长写回 TimeStore 校准，实现"账本兜底"：
 * - 只取 max(账本, 已存)，不覆盖更准的实时值；
 * - 当天正处于活动会话的被计时应用跳过（由状态机结算，账本已含实时增量，避免双计）；
 * - 无使用情况访问权限时直接跳过。
 */
class UsageReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_NAME = "usage_reconcile"
        const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageReconcileWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * 手动/周期对账一次：把 UsageStats 每日前台时长回填进 TimeStore。
         * suspend，建议在后台线程调用（queryEvents 可能较慢）。
         */
        suspend fun reconcileNow(context: Context) {
            val ctx = context.applicationContext
            if (!UsageStatsLedger.hasUsageAccess(ctx)) return

            val activePkg = AppForegroundTracker.currentPackage()
            val today = LocalDate.now()
            for (pkg in TrackedApps.trackedPackages()) {
                for (daysAgo in 6 downTo 0) {
                    val date = today.minusDays(daysAgo.toLong())
                    // 当天活动会话由状态机结算（displayMs 已含实时增量），跳过以免双计
                    if (daysAgo == 0 && pkg == activePkg) continue
                    val ledger = UsageStatsLedger.getDayForegroundMs(ctx, pkg, date)
                    val stored = TimeStore.getAccumulatedMs(pkg, date)
                    if (ledger > stored) TimeStore.setAccumulatedMs(pkg, ledger, date)
                }
            }
        }
    }

    override suspend fun doWork(): Result {
        reconcileNow(applicationContext)
        return Result.success()
    }
}
