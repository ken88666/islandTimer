package com.hsh.islandtimer

import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import com.hsh.islandtimer.core.AppForegroundTracker
import com.hsh.islandtimer.databinding.ActivityDiagnosticsBinding
import com.hsh.islandtimer.island.IslandDiagnostics
import com.hsh.islandtimer.island.IslandNotifier

/**
 * 诊断二级页：无需 adb 的上岛诊断 + 常驻测试通知。
 *
 * 测试通知走独立 ID（1002），可与正式计时通知（1001）共存；
 * 离开本页不自动撤销，需要撤销时回本页点按钮。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val basePadding = (20 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = basePadding + bars.left,
                top = basePadding + bars.top,
                right = basePadding + bars.right,
                bottom = basePadding + bars.bottom
            )
            insets
        }

        binding.btnDiagnose.setOnClickListener { refreshDiagnostics() }
        binding.btnTestIsland.setOnClickListener { toggleTestIsland() }
        syncTestButton()
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        syncTestButton()
    }

    private fun toggleTestIsland() {
        if (IslandNotifier.isTestIslandShowing(this)) {
            IslandNotifier.hideTestIsland(this)
            Snackbar.make(binding.root, "测试通知已撤销", Snackbar.LENGTH_SHORT).show()
        } else {
            IslandNotifier.showTestIsland(this)
            Snackbar.make(binding.root, "已显示测试常驻通知", Snackbar.LENGTH_SHORT).show()
        }
        syncTestButton()
        refreshDiagnostics()
    }

    /** 按钮文字跟随测试通知的真实状态 */
    private fun syncTestButton() {
        binding.btnTestIsland.text =
            if (IslandNotifier.isTestIslandShowing(this)) "撤销测试通知" else "常驻通知上岛测试"
    }

    /** 无需 adb 的上岛诊断：系统能力 + 渠道 + 样例通知 extras + 活跃通知 */
    private fun refreshDiagnostics() {
        val sb = StringBuilder()
        sb.append("系统 Android API ").append(Build.VERSION.SDK_INT).append("（HyperOS3=36）\n")
        sb.append(IslandDiagnostics.canPostPromoted(this)).append('\n')
        sb.append(IslandDiagnostics.channelInfo(this)).append('\n')
        sb.append('\n')
        sb.append(IslandDiagnostics.sampleNotificationExtras(this)).append('\n')
        sb.append('\n')
        sb.append(IslandDiagnostics.activeNotifications(this))
        sb.append('\n')
        sb.append("--- 最近前台事件 ---\n")
        sb.append(AppForegroundTracker.recentEvents().ifBlank { "(空)" })
        binding.tvDiagnostics.text = sb.toString()
    }
}
