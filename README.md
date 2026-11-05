# 节律岛（islandTimer）

在小米 HyperOS「超级岛」上显示**被计时应用的今日累计使用时长**的 Android 工程骨架。

- 打开被计时的应用（如抖音）→ 超级岛出现胶囊，从今日已累计时长开始计时；
- 退到桌面 → 停止计时，超级岛消失；
- 下午再打开 → 从上午攒下的时长继续累计；
- 支持每日/每周刷新、自由配置被计时应用。

> 本骨架走的是 **「基础岛」路线**：HyperOS 4 会把 AOSP 常驻(ongoing)通知自动抬上超级岛
> （显示 smallIcon + 短文案），**不需要小米官方超级岛白名单**。富样式（大岛/进度条/图片）见
> `island/HyperIslandHelper.kt`，需要申请官方权限后才能生效。

## AI 构建声明

本项目代码 **100% 由 AI 构建**，需求定义、真机验证与功能测试由人工完成。

---

## 架构

```
                     ┌──────────────────────────────────────────┐
  前台应用切换  ──►  │  ForegroundDetectorService (Accessibility) │  ← 实时触发 + 保活锚点
  (亚秒级窗口事件)    │  · 只监听 TYPE_WINDOW_STATE_CHANGED         │
                     │  · canRetrieveWindowContent = false       │
                     └───────────────┬──────────────────────────┘
                                     │ onForegroundChanged(pkg, isLauncher, now)
                                     ▼
                     ┌──────────────────────────────────────────┐
                     │        AppForegroundTracker (状态机)       │
                     │  currentPkg / sessionStart / todayAccum    │
                     └───────┬──────────────────────┬────────────┘
                             │ show()/hide()        │ 读写
                             ▼                      ▼
                ┌────────────────────┐   ┌──────────────────────┐
                │  UsageTimerService │   │  TimeStore (按天分桶) │
                │  (前台服务+常驻通知) │   │  TrackedApps (配置)  │
                │  = 基础岛载体        │   │  UsageStatsLedger(账本)│
                └────────────────────┘   └──────────────────────┘
```

**三个关键设计点：**

1. **快速响应**：无障碍服务的 `TYPE_WINDOW_STATE_CHANGED` 事件亚秒级触发，负责"实时"；
2. **时长准确**：`UsageStatsManager` 作为权威账本（`UsageStatsLedger`），可补历史、可对账；
3. **低功耗**：纯事件驱动 + 15s 节流刷新通知文案；空闲时前台服务降级（撤通知=岛消失），
   由无障碍服务负责长期保活。

---

## 目录结构

```
app/src/main/java/com/hsh/islandtimer/
├── App.kt                          # Application：初始化单例
├── MainActivity.kt                 # 权限状态 + 启动 + 选择被计时应用
├── core/
│   ├── AppForegroundTracker.kt     # 状态机：连续时长/今日累计
│   ├── TrackedApps.kt              # 被计时应用集合 + 枚举可启动应用
│   ├── TimeStore.kt                # 按天分桶持久化（每日/每周刷新）
│   └── UsageStatsLedger.kt         # UsageStats 权威账本
├── service/
│   ├── ForegroundDetectorService.kt# 无障碍服务（实时前台检测）
│   └── UsageTimerService.kt        # 前台服务 + 常驻通知（基础岛）
├── keepalive/
│   └── KeepAliveHelper.kt          # MIUI 自启动/电池优化/权限跳转
├── island/
│   ├── IslandNotifier.kt           # 通知构造（基础岛）
│   └── HyperIslandHelper.kt        # 富样式 miui.focus 扩展（可选，需鉴权）
└── ui/
    └── AppListAdapter.kt           # 应用勾选列表
```

---

## 环境与构建

| 项 | 值 |
|---|---|
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |
| Gradle | 8.7 |
| compileSdk / targetSdk | 34 |
| minSdk | 26（Android 8.0，java.time 无需脱糖） |
| 依赖 | core-ktx / appcompat / material / constraintlayout / recyclerview / coroutines |

用 Android Studio 打开本目录（`IslandTimer/`）即可。AS 会识别 Gradle 配置并同步；
若提示 Gradle 版本缺失，AS 会自动下载 8.7 或按向导补全 wrapper。

首次运行前需授予两项权限（App 内"检查并启动"会引导）：

1. **使用情况访问**（`PACKAGE_USAGE_STATS`）→ 设置 → 使用情况访问；
2. **无障碍服务**（本 App）→ 设置 → 无障碍。

HyperOS 保活四件套（App 内有入口）：

- 忽略电池优化
- MIUI 自启动
- 省电策略 → 无限制（手动：设置 → 应用 → 本应用 → 省电策略）
- 最近任务 → 长按 → 锁定

---

## 每日/每周刷新

`TimeStore` 以 `日期|包名` 为 key 按天分桶，天然实现"每日刷新"；
`TimeStore.getWeekMs()` 汇总最近 7 天实现"每周刷新"。展示时：

- 今日展示值 = `todayAccumulatedMs + (elapsedRealtime - sessionStartRealtime)`；
- 上午退出时已把会话时长结算进 `todayAccumulatedMs`，所以下午重开从上午的累计继续。

## 富样式超级岛（第二阶段）

基础岛零审核即可用。要解锁大岛/进度条/图片，需按小米官方接入指南申请：

1. 注册小米开发者账号 → 创建并上架应用；
2. 开发者后台 → 目标应用 → 申请开通超级岛 → 配置指纹证书 → 提交上岛场景预审；
3. 审核通过 → 联调 → 申请设备白名单自测 → 提交正式 APK → 灰度 7~15 天全量。

可引入 [HyperIsland-ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit) 封装
`miui.focus.*` payload（`implementation("io.github.d4viddf:hyperisland_kit:0.4.5")`），
本骨架的 `HyperIslandHelper.kt` 已预留接口。

参考项目：[WorkIsland-HyperOS4](https://github.com/Ralsei1337/workisland-hyperos4)
（HyperOS 4 实测：不需要鉴权即可上基础岛）。

---

## 已知边界

- 无障碍服务被用户关闭后计时停止，属预期（App 内状态会显示"未开启"）。
- `UsageStats` 事件有秒级延迟，故只做账本，不做实时触发。
- 基础岛文案为"应用名 + 已使用时长"；进度条/大岛展开需官方权限。
- 息屏（屏幕关闭）也会结束当前会话，避免锁屏后计时虚增（可通过监听 `ACTION_SCREEN_OFF` 扩展）。
