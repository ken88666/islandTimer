package com.hsh.islandtimer.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hsh.islandtimer.databinding.ItemAppBinding
import com.hsh.islandtimer.island.IslandNotifier

class AppListAdapter : ListAdapter<AppListAdapter.AppItem, AppListAdapter.VH>(DIFF) {

    data class AppItem(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        var tracked: Boolean,
        val todayMs: Long,
        val weekMs: Long
    )

    var onChecked: ((String, Boolean) -> Unit)? = null

    private fun usageText(item: AppItem): String = if (item.tracked) {
        "今日 ${IslandNotifier.format(item.todayMs)} · 本周 ${IslandNotifier.format(item.weekMs)}"
    } else {
        "未启用"
    }

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.binding.appIcon.setImageDrawable(item.icon)
        holder.binding.appLabel.text = item.label
        holder.binding.appUsage.text = usageText(item)
        holder.binding.appCheck.setOnCheckedChangeListener(null)
        holder.binding.appCheck.isChecked = item.tracked
        holder.binding.appCheck.setOnCheckedChangeListener { _, checked ->
            item.tracked = checked
            holder.binding.appUsage.text = usageText(item)
            onChecked?.invoke(item.pkg, checked)
        }
    }

    companion object {
        /** 包名是稳定标识；内容变化只看文字、勾选状态和时长。 */
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                oldItem.pkg == newItem.pkg

            override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                oldItem.label == newItem.label &&
                    oldItem.tracked == newItem.tracked &&
                    oldItem.todayMs == newItem.todayMs &&
                    oldItem.weekMs == newItem.weekMs
        }
    }
}
