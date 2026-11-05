package com.hyperland.islandtimer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.hyperland.islandtimer.core.AppForegroundTracker
import com.hyperland.islandtimer.core.TimeStore
import com.hyperland.islandtimer.core.TrackedApps
import com.hyperland.islandtimer.core.UsageReconcileWorker
import com.hyperland.islandtimer.core.UsageStatsLedger
import com.hyperland.islandtimer.databinding.ActivityMainBinding
import com.hyperland.islandtimer.island.IslandNotifier
import com.hyperland.islandtimer.keepalive.KeepAliveHelper
import com.hyperland.islandtimer.service.ForegroundDetectorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var reconcileJob: Job? = null
    @Volatile private var reconciling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.btnUsage.setOnClickListener { KeepAliveHelper.openUsageAccess(this) }
        binding.btnAccessibility.setOnClickListener { KeepAliveHelper.openAccessibilitySettings(this) }
        binding.btnBattery.setOnClickListener { KeepAliveHelper.openIgnoreBatteryOptimization(this) }
        binding.btnAutostart.setOnClickListener {
            if (!KeepAliveHelper.openMiuiAutostart(this)) {
                Toast.makeText(this, "非 MIUI/HyperOS 设备，请在系统设置中手动允许自启动", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnIsland.setOnClickListener { KeepAliveHelper.openAppNotificationSettings(this) }
        binding.btnManageApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }
        binding.btnMore.setOnClickListener { view ->
            PopupMenu(this, view).apply {
                menu.add(0, 1, 0, "诊断")
                setOnMenuItemClickListener { item ->
                    if (item.itemId == 1) {
                        startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                        true
                    } else {
                        false
                    }
                }
                show()
            }
        }
        binding.btnStart.setOnClickListener { onStartClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshSummary()
    }

    private fun onStartClicked() {
        val okUsage = UsageStatsLedger.hasUsageAccess(this)
        val okAccessibility = ForegroundDetectorService.isEnabled(this)
        if (!okUsage || !okAccessibility) {
            Toast.makeText(this, "请先开启「使用情况访问」和「无障碍服务」", Toast.LENGTH_LONG).show()
            return
        }
        // 连点保护：进行中直接忽略
        if (reconciling) return

        reconciling = true
        binding.btnStart.isEnabled = false
        binding.btnStart.text = "加载中"

        reconcileJob?.cancel()
        reconcileJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    UsageReconcileWorker.reconcileNow(this@MainActivity)
                }
            }
            refreshSummary()
            reconciling = false
            binding.btnStart.text = "检查并启动"
            binding.btnStart.isEnabled = true
            Snackbar.make(binding.root, "已就绪", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus() {
        val colorNormal = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        val colorError = MaterialColors.getColor(
            binding.root,
            androidx.appcompat.R.attr.colorError
        )

        val okUsage = UsageStatsLedger.hasUsageAccess(this)
        binding.tvUsageStatus.text = if (okUsage) "已授权" else "未授权"
        binding.tvUsageStatus.setTextColor(if (okUsage) colorNormal else colorError)

        val okAccessibility = ForegroundDetectorService.isEnabled(this)
        binding.tvAccessibilityStatus.text = if (okAccessibility) "已开启" else "未开启"
        binding.tvAccessibilityStatus.setTextColor(if (okAccessibility) colorNormal else colorError)

        val okBattery = KeepAliveHelper.isIgnoringBattery(this)
        binding.tvBatteryStatus.text = if (okBattery) "已忽略" else "未忽略"
        binding.tvBatteryStatus.setTextColor(if (okBattery) colorNormal else colorError)
    }

    /** 今日/本周合计（含正在进行的活动会话） */
    private fun refreshSummary() {
        val tracked = TrackedApps.trackedPackages()
        val activePkg = AppForegroundTracker.currentPackage()
        val liveMs = AppForegroundTracker.liveSessionMs()
        var today = 0L
        var week = 0L
        for (pkg in tracked) {
            val live = if (pkg == activePkg) liveMs else 0L
            today += TimeStore.getTodayMs(pkg) + live
            week += TimeStore.getWeekMs(pkg) + live
        }
        binding.tvTodayTotal.text = "今日合计：" + IslandNotifier.format(today)
        binding.tvWeekTotal.text = "本周合计：" + IslandNotifier.format(week)
    }
}
