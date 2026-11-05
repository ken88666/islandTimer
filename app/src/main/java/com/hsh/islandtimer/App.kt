package com.hsh.islandtimer

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.hsh.islandtimer.core.AppForegroundTracker
import com.hsh.islandtimer.core.TimeStore
import com.hsh.islandtimer.core.TrackedApps
import com.hsh.islandtimer.core.UsageReconcileWorker
import com.hsh.islandtimer.island.IslandNotifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You 动态取色（Android 12+ 自动跟随系统壁纸与深浅色主题）
        DynamicColors.applyToActivitiesIfAvailable(this)
        TimeStore.init(this)
        TrackedApps.init(this)
        AppForegroundTracker.bind(this)
        IslandNotifier.ensureChannel(this)
        // UsageStats 账本周期对账（15min 兜底校准）
        UsageReconcileWorker.schedule(this)
    }
}
