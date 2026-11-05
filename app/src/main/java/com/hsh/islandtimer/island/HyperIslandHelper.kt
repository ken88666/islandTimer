package com.hsh.islandtimer.island

import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * 富样式超级岛扩展（拿到小米官方白名单 / APP_ID 后再启用）。
 *
 * HyperOS 通过未公开的 `miui.focus.*` Bundle extra 控制大岛/小岛/图片/进度条。
 * 未通过鉴权（APP_ID + 指纹证书）时，系统会静默忽略这些参数，只显示基础胶囊。
 *
 * 更省心的方式：引入官方封装库 HyperIsland-ToolKit：
 *   implementation("io.github.d4viddf:hyperisland_kit:0.4.5")
 * 文档：https://github.com/D4vidDf/HyperIsland-ToolKit
 */
object HyperIslandHelper {

    /**
     * 计时器/进度条模板的岛内扩展参数（key 为示意值，联调时以官方模板库为准）。
     * @param title    大岛标题
     * @param subtitle 副标题
     * @param progress 0~100 进度
     */
    fun buildTimerIslandExtras(title: String, subtitle: String, progress: Int): Bundle {
        return Bundle().apply {
            putString("miui.focus.param.title", title)
            putString("miui.focus.param.subtitle", subtitle)
            putInt("miui.focus.param.progress", progress.coerceIn(0, 100))
            putInt("miui.focus.param.type", 3) // 3 = 计时器/进度条模板（示意值）
        }
    }

    fun applyTo(builder: NotificationCompat.Builder, extras: Bundle) {
        builder.addExtras(extras)
    }
}
