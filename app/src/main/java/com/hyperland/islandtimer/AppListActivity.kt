package com.hyperland.islandtimer

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.hyperland.islandtimer.core.AppForegroundTracker
import com.hyperland.islandtimer.core.TimeStore
import com.hyperland.islandtimer.core.TrackedApps
import com.hyperland.islandtimer.databinding.ActivityAppListBinding
import com.hyperland.islandtimer.ui.AppListAdapter

/**
 * 二级页面：选择要计时的应用。
 * 顶部搜索框按应用名/包名实时过滤列表。
 */
class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private val adapter = AppListAdapter()
    private var allItems: List<AppListAdapter.AppItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
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

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        adapter.onChecked = { pkg, checked ->
            TrackedApps.setTracked(pkg, checked)
            // 实时重排：刚勾选的应用立即置顶（ListAdapter 自带移动动画，无需手写）
            applyFilter(binding.searchInput.text?.toString().orEmpty())
        }

        binding.searchInput.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        loadApps()
    }

    private fun loadApps() {
        val tracked = TrackedApps.trackedPackages()
        val activePkg = AppForegroundTracker.currentPackage()
        val liveMs = AppForegroundTracker.liveSessionMs()
        allItems = TrackedApps.launchableApps(this).map {
            val live = if (it.packageName == activePkg) liveMs else 0L
            AppListAdapter.AppItem(
                it.packageName,
                it.label,
                it.icon,
                tracked.contains(it.packageName),
                TimeStore.getTodayMs(it.packageName) + live,
                TimeStore.getWeekMs(it.packageName) + live
            )
        }
        applyFilter(binding.searchInput.text?.toString().orEmpty())
    }

    /** 按应用名/包名实时过滤（不区分大小写），已勾选的置顶。 */
    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = (if (q.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
            }
        }).sortedWith(
            compareByDescending<AppListAdapter.AppItem> { it.tracked }
                .thenBy { it.label.lowercase() }
        )
        adapter.submitList(filtered)
        binding.emptyView.visibility = if (q.isNotEmpty() && filtered.isEmpty()) View.VISIBLE else View.GONE
    }
}
