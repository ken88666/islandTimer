package com.hyperland.islandtimer.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * 被计时应用的集合（持久化） + 枚举可启动应用供勾选。
 */
object TrackedApps {
    private const val PREFS = "tracked_apps"
    private const val KEY_TRACKED = "tracked_packages"
    private lateinit var prefs: SharedPreferences

    data class AppInfo(val packageName: String, val label: String, val icon: Drawable?)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isTracked(pkg: String): Boolean =
        prefs.getStringSet(KEY_TRACKED, emptySet())?.contains(pkg) == true

    fun setTracked(pkg: String, tracked: Boolean) {
        val set = prefs.getStringSet(KEY_TRACKED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (tracked) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet(KEY_TRACKED, set).apply()
    }

    fun trackedPackages(): Set<String> = prefs.getStringSet(KEY_TRACKED, emptySet()) ?: emptySet()

    fun labelFor(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (e: Exception) {
        pkg
    }

    /** 枚举所有可启动应用（Android 11+ 靠 <queries> 保证可见性） */
    fun launchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val seen = HashSet<String>()
        val out = mutableListOf<AppInfo>()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (!seen.add(pkg)) continue
            if (pkg == context.packageName) continue // 排除自己
            out.add(AppInfo(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
        }
        return out.sortedBy { it.label }
    }
}
