package com.hyperland.islandtimer

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.hyperland.islandtimer.core.AppForegroundTracker
import com.hyperland.islandtimer.core.TimeStore
import com.hyperland.islandtimer.core.TrackedApps
import com.hyperland.islandtimer.core.UsageReconcileWorker
import com.hyperland.islandtimer.island.IslandNotifier

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
