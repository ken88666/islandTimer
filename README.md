# 节律岛（islandTimer）

在小米 HyperOS「超级岛」上显示**被计时应用的今日累计使用时长**的 Android 工程骨架。

- 打开被计时的应用（如抖音）→ 超级岛出现胶囊，从今日已累计时长开始计时；
- 退到桌面 → 停止计时，超级岛消失；
- 下午再打开 → 从上午攒下的时长继续累计；
- 支持每日/每周刷新、自由配置被计时应用。

> 本工程走的是 **「基础岛」路线**：走 Android 16 原生 **Live Updates（promoted ongoing）** 通知——
> 在通知上真正调用 `setShortCriticalText()` + `setRequestPromotedOngoing()`（androidx.core 1.17+）后，
> 系统把 `smallIcon + 短文案` 显示成超级岛胶囊，**不需要小米官方白名单，也与签名无关**。
>
> 该结论已实测（HyperOS 3.0.307 国行，2026-11-04），不是"等 HyperOS 4 才支持"：
> 早期用 raw extras / 反射改不到 Notification 内部字段，`hasPromotableCharacteristics()` 恒为 false，
> 必须走上面的公开 API；manifest 需声明 `POST_PROMOTED_NOTIFICATIONS`，用户还需在本应用通知设置里
> 打开「焦点通知 / 实时活动」开关。
>
> 富样式（大岛/进度条/图片）见 `island/HyperIslandHelper.kt`，需要申请官方权限（白名单 + 指纹证书）后才能生效。

## AI 构建声明

本项目代码 **100% 由 AI 构建**，需求定义、真机验证与功能测试由人工完成。

---

## 架构

```
                     ┌──────────────────────────────────────────┐
  前台应用切换  ──►  │  ForegroundDetectorService (Accessibility) │  ← 实时触发 + 保活锚点
  (亚秒级窗口事件)    │  · 只监听 TYPE_WINDOW_STATE_CHANGED         │
                     │  · canRetrieveWindowContent = true        │
                     │    （hide 前核实真实前台包名，不读文字内容） │
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
                │  前台服务：只做保活  │   │  TrackedApps (配置)  │
                │  + 1s 心跳刷新岛文案 │   │  UsageStatsLedger(账本)│
                └─────────┬──────────┘   └──────────────────────┘
                          │ notify()（不走 startForeground）
                          ▼
                ┌────────────────────┐
                │   IslandNotifier   │  ← 基础岛载体：promoted ongoing
                │  ID 1001 岛通知     │     通知，稳定上岛
                └────────────────────┘
```

**三个关键设计点：**

1. **快速响应**：无障碍服务的 `TYPE_WINDOW_STATE_CHANGED` 事件亚秒级触发，负责"实时"；
2. **时长准确**：`UsageStatsManager` 作为权威账本（`UsageStatsLedger`），可补历史、可对账
   （`UsageReconcileWorker` 每 15 分钟 + 手动触发回填）；
3. **低功耗**：纯事件驱动 + `UsageTimerService.TICK_MS = 1s` 心跳只刷新岛文案；
   空闲时 `stopForeground(REMOVE)` + `cancel(1001)` 撤岛，由无障碍服务负责长期保活。
   **岛通知走 `notify()`，不走前台服务通知**——HyperOS 3 上前台服务通知会被挤下岛。

---

## 目录结构

```
app/src/main/java/com/hsh/islandtimer/     # 共 16 个 .kt
├── App.kt                          # Application：初始化单例 + 调度对账 Worker
├── MainActivity.kt                 # 主界面：权限状态 / 保活入口 / 今日·本周合计 / 诊断入口 / 检查并启动
├── AppListActivity.kt              # 二级页：选择被计时应用（实时搜索 + 已勾选置顶）
├── DiagnosticsActivity.kt          # 二级页：诊断面板 + 刷新诊断 + 常驻测试通知
├── core/
│   ├── AppForegroundTracker.kt     # 状态机：连续时长/今日累计/息屏暂停/防误隐藏
│   ├── TrackedApps.kt              # 被计时应用集合 + 枚举可启动应用
│   ├── TimeStore.kt                # 按天分桶持久化（每日/每周刷新）
│   ├── UsageStatsLedger.kt         # UsageStats 权威账本
│   └── UsageReconcileWorker.kt     # 账本对账：15min 周期 + reconcileNow() 手动触发
├── service/
│   ├── ForegroundDetectorService.kt# 无障碍服务（实时前台检测 + 保活锚点 + 息屏广播 + 前台核实）
│   └── UsageTimerService.kt        # 前台服务：最小化保活通知（1003）+ 1s 心跳刷新岛（1001）
├── keepalive/
│   └── KeepAliveHelper.kt          # MIUI 自启动/电池优化/权限跳转
├── island/
│   ├── IslandNotifier.kt           # 基础岛通知构造（1001 岛 / 1002 测试，notify 通道 + promoted 标记）
│   ├── IslandDiagnostics.kt        # 无 adb 诊断（extras 回读 / 能力查询 / 活跃通知）
│   └── HyperIslandHelper.kt        # 富样式 miui.focus 扩展（可选，需鉴权）
└── ui/
    └── AppListAdapter.kt           # 应用勾选列表（含「今日 · 本周」时长）

docs/                               # GitHub Pages 源：隐私政策（纯 HTML）+ 超级岛预审材料草稿
README.md / DEV_HANDOFF.md / LICENSE # 说明、开发交接、MIT
release.keystore + keystore.properties  # 发布签名（已在 .gitignore，不入库；缺失时回退 debug 签名）
```

---

## 环境与构建

| 项 | 值 |
|---|---|
| AGP | 8.9.2 |
| Kotlin | 2.1.0 |
| Gradle | 8.11.1（wrapper） |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 26（Android 8.0，java.time 无需脱糖） |
| Java / jvmTarget | 17（构建用 JDK 21，见下） |
| 依赖 | core-ktx 1.17.0 / appcompat 1.7.0 / material 1.12.0 / constraintlayout 2.1.4 / recyclerview 1.3.2 / lifecycle-runtime-ktx 2.8.3 / coroutines-android 1.8.1 / work-runtime-ktx 2.9.1 |

> 为什么是这套版本：`setRequestPromotedOngoing()` 需要 androidx.core **1.17+**，
> 而它要求 compileSdk 36 + AGP 8.9.1+（连带 Gradle 8.11.1 + Kotlin 2.1.0）。

用 Android Studio 打开本目录（`IslandTimer/`）即可，AS 会按 wrapper 下载 Gradle 8.11.1。
命令行构建须指定 JDK 17+（系统 PATH 里的 Java 8 不行）：

```powershell
$env:JAVA_HOME = "<JDK 17 或 21 路径>"
.\gradlew.bat assembleDebug
```

发布产物：`assembleRelease` → `app/build/outputs/apk/release/`（小米/自签渠道用 APK）；
`bundleRelease` → `app/build/outputs/bundle/release/`（Google Play 用 AAB）。

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
- 息屏（`ACTION_SCREEN_OFF`）立即结算并撤岛，避免锁屏后计时虚增；解锁（`ACTION_USER_PRESENT`）
  时若被计时应用仍在前台则自动续计。
- 本应用无联网权限、无账号、无第三方 SDK；数据只存在设备本地。
  隐私政策：https://ken88666.github.io/islandTimer/privacy-policy.html （中文
  [privacy-policy-zh.html](https://ken88666.github.io/islandTimer/privacy-policy-zh.html)），
  源文件在 `docs/`。

---

## 许可

MIT，见 [LICENSE](LICENSE)。`Copyright (c) 2026 ken88666`

