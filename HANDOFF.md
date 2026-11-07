# 会话交接包 · 节律岛 App（islandTimer）

> 本文件是整个会话的完整交付摘要。新会话只需读本文件 + 现有代码（`app/` 目录）即可无缝续接，无需重新查证。
>
> 最后更新：2026-11-08

---

## 0. 一句话目标

在小米 **HyperOS「超级岛」** 上做一款"前台应用使用时长计时"工具 App：

- 打开被计时的应用（如抖音）→ 超级岛出现胶囊，从**今日累计时长**开始计时；
- 退到桌面 → 停止计时，超级岛消失；
- 下午再打开 → 从上午攒下的时长**继续累计**；
- 支持**每日/每周刷新**、**被计时应用自由配置**；
- 硬约束：**免 root**、**快速响应**、**长时间后台低功耗待机**。

---

## 1. 需求细节（用户原话要点）

> "免root实现快速响应地读取前台应用使用并记录连续使用时间、长时间保留后台低功耗待机。比如早上打开抖音，灵动岛从零分钟开始计时，刷一小时退到桌面停止计时、岛消失。下午再打开，岛从上午1h处开始继续计时。计时可设每天刷新/每周刷新，被计时软件自由配置。"

---

## 2. 研究结论（已查证，勿重复调研）

### 2.1 小米「超级岛」分两档

| 档位 | 机制 | 是否需官方白名单 | 与需求契合度 |
|---|---|---|---|
| **基础岛** | **Android 16「Live Updates / promoted ongoing」通知**：`NotificationCompat.Builder.setShortCriticalText()` + `.setRequestPromotedOngoing(true)`，系统显示 `smallIcon + shortCriticalText` 胶囊 | **不需要**（与签名无关，debug 包即可） | 100%，当前实现走这条 |
| **正式超级岛** | 通过 `miui.focus.param` 解锁大岛/小岛/图片/进度条等 23+ 模板 | **需要**（申请+场景预审+指纹证书+灰度） | 部分契合，有 3 个审核风险点（见 §5） |

> 关键事实（真机验证 2026-11-04，HyperOS 3.0.307 国行，包名 com.hsh.islandtimer）：
> - 基础岛走的是 **Android 16 原生 Live Updates（promoted ongoing）**，不是「任意常驻通知」；
> - 必须真正调用 `setShortCriticalText()` + `setRequestPromotedOngoing()`（androidx.core 1.17+），**raw extras / 反射都设不到 Notification 内部字段**，否则 `hasPromotableCharacteristics()` 恒为 false；
> - 需要 manifest 声明 `POST_PROMOTED_NOTIFICATIONS`；
> - 需要用户在本应用通知设置里开启「焦点通知 / 实时活动」开关；
> - 官方 `miui.focus.param` 未鉴权时会被系统静默忽略。

### 2.2 小米官方超级岛接入流程（2025-10-23 发布）

1. 注册小米开发者账号 → 创建并上架应用；
2. 开发者后台选目标应用 → **申请开通超级岛** → 配置**指纹证书** → 提交**上岛场景预审材料**；
3. 审核通过 → 开发联调 → 申请**设备白名单**自测 → 提交正式 APK；
4. 审核通过获正式权限 → **灰度发布 7~15 天后全量**。

**两种通道**：客户端原生通知 / MIPUSH（`regId`，焦点通知）。
**资源规范**：单图 ≤100KB、HTTPS、宽高比 1.78:1（16:9）~ 1:1；样式必须遵循"超级岛模板库"。
**契合点**：需求落点恰好是官方「计时器/进度条」模板之一。

### 2.3 审核风险点（第二阶段上正式岛时需针对性设计）

1. **常驻时长边界**：用户刷 1h 抖音岛就挂 1h，可能被理解为"常驻占岛"。对策：进应用大岛显示几秒→折叠成胶囊只显示 `⏱ 12:34`；明确"仅被计时应用前台时出现，退出即消失"。
2. **无障碍权限正当性**：商店审核会盘问无障碍用途。对策：写明"只用于检测前台应用统计时长，不读内容、不模拟操作"，并做"仅 UsageStats 的降级模式"把无障碍做成可选增强。
3. **指纹证书稳定性**：正式岛靠"指纹证书+APP_ID"鉴权，必须从第一天用正式签名。

---

## 3. 技术方案

### 3.1 免root快速前台检测 = 双通道

| 方案 | 响应 | 权威性 | 免root | 结论 |
|---|---|---|---|---|
| `getRunningTasks` | — | — | 已废弃 | ❌ |
| 读 `/proc/*/cmdline` | 快 | 准 | **需 root** | ❌ |
| `UsageStatsManager` 轮询 | 秒~分钟级延迟 | 权威、可补历史 | ✅ | 做**账本/校准** |
| **AccessibilityService** 窗口事件 | **亚秒级** | 准、实时 | ✅ | 做**触发层** |

**结论：无障碍（触发）+ UsageStats（账本）双通道，是免root下唯一同时满足"快速响应"和"时长准确"的组合。**

- 无障碍监听 `TYPE_WINDOW_STATE_CHANGED`，`canRetrieveWindowContent="true"`（只为 hide 前用 `getRootInActiveWindow()` 核实真实前台包名，不读文字内容）。
- 桌面包名 `com.miui.home` = 退桌面判断依据。
- 需过滤系统浮层包：`miui.systemui.plugin`、`com.android.quicksearchbox`、`com.miui.systemui` 等（下拉通知栏/搜索框会误触发「切应用」）。
- UsageStats 用 `MOVE_TO_FOREGROUND/BACKGROUND`（API 29+），老版本用 `ACTIVITY_RESUMED/PAUSED`；`INTERVAL_DAILY/WEEKLY` 天然适配每日/每周刷新。

### 3.2 连续时长状态机（核心语义）

区分两个量：
- `todayAccumulatedMs`：今日已累计（持久化）；
- `sessionStartRealtime`：本次会话起点（内存态 `elapsedRealtime`）。

**展示值 = `todayAccumulatedMs + (elapsedRealtime - sessionStartRealtime)`**。

流程：进被计时应用 → 记 `sessionStart`、读 `todayAccumulated` → 岛出现；退桌面 → 结算 `todayAccumulated += (now - sessionStart)`、岛消失；下午重开 → `todayAccumulated` 已是上午的 1h，从 1h 续走。

### 3.3 后台保活与上岛通知分离（关键踩坑）

1. **岛通知必须走 `notify()`，不能走 `startForeground()`**：前台服务通知在 HyperOS 3 上会被挤下岛/不稳定；`notify()` 发的 promoted 通知稳定。
2. **前台服务只做保活**：`startForeground()` 用一条最小化通知（渠道 `IMPORTANCE_DEFAULT`），与岛通知（`notify()`，ID 1001）分离。
3. **保活优先级（真机实测）**：IME ≥ 悬浮窗 > 前台服务（带可见通知）> 无障碍 > 普通后台。前台服务渠道用 `IMPORTANCE_MIN` 会被 HyperOS 当「不可见」进而冻结/杀进程。
4. **HyperOS 保活四件套**：忽略电池优化 / MIUI 自启动 / 省电策略=无限制 / 最近任务锁定。
5. **低功耗**：事件驱动 + 1s 心跳刷新岛文案（时长精确到秒）；空闲 `stopForeground(REMOVE)` + `notify().cancel()`；不用 `PARTIAL_WAKE_LOCK`。

**防误隐藏（关键）**：启动动画/闪屏/下拉通知栏/搜索框会产生瞬态「桌面/非被计时应用」窗口事件，导致 show→hide 闪岛甚至掉岛。解法：
- 过滤系统浮层包（`miui.systemui.plugin`、`com.android.quicksearchbox` 等）；
- hide 加 500ms 防抖 + show 后 2s 宽限期；
- hide 真正执行前用 `getRootInActiveWindow()` 核实真实前台，误判则取消隐藏。

**息屏必须无条件隐藏（边界坑）**：`onScreenOff()` 不能走「核实前台」——息屏瞬间 `getRootInActiveWindow()` 返回的还是原应用，会被误判成「仍在前台」而取消隐藏，导致岛卡在旧应用上。所以 `settleAndHide(verify)` 里，息屏路径用 `verify=false` 直接结算撤岛，只有防抖隐藏才做核实。

---

## 4. 代码现状（已实现，位于 `app/`）

```
app/src/main/java/com/hsh/islandtimer/
├── App.kt                          # Application：初始化单例 + 调度对账 Worker
├── MainActivity.kt                 # 主界面：权限/保活/今日周合计/诊断 + 检查启动（对账转圈防连点）
├── AppListActivity.kt              # 二级页：选择要计时的应用（搜索/已勾选置顶）
├── DiagnosticsActivity.kt          # 二级页：诊断面板 + 刷新诊断 + 常驻测试通知
├── core/
│   ├── AppForegroundTracker.kt     # ★ 状态机（连续时长/今日累计/息屏暂停）
│   ├── TimeStore.kt                # 按天分桶 → 每日/每周刷新（getWeekMs）
│   ├── TrackedApps.kt              # 被计时应用集合 + 枚举可启动应用
│   ├── UsageStatsLedger.kt         # UsageStats 权威账本（getDayForegroundMs）
│   └── UsageReconcileWorker.kt     # 账本对账：15min 周期 + reconcileNow() 手动触发
├── service/
│   ├── ForegroundDetectorService.kt# 无障碍：前台检测 + 保活锚点 + 息屏广播 + 浮层过滤 + 前台核实
│   └── UsageTimerService.kt        # 前台服务：最小化保活通知 + 1s 心跳（岛通知走 notify）
├── keepalive/KeepAliveHelper.kt    # MIUI 自启动/电池优化/权限跳转
├── island/
│   ├── IslandNotifier.kt           # 基础岛通知构造（notify 通道 + promoted 标记）
│   ├── IslandDiagnostics.kt        # 无 adb 诊断（extras 回读/能力查询/活跃通知）
│   └── HyperIslandHelper.kt        # 富样式 miui.focus（可选，需鉴权）
└── ui/AppListAdapter.kt            # 列表项含「今日 · 本周」时长
```

关键实现点：
- `AppForegroundTracker.onForegroundChanged()` 已实现"上午攒/下午续计"逻辑 + 防误隐藏（防抖/宽限期/前台核实）。
- **息屏暂停**：`ForegroundDetectorService` 动态注册 `ACTION_SCREEN_OFF`/`ACTION_USER_PRESENT`（受保护广播）；屏灭立即结算撤岛并拒绝前台事件，解锁后若被计时应用仍在前台自动续计，杜绝锁屏虚增。
- `UsageTimerService`：前台服务用最小化通知保活；岛通知走 `IslandNotifier.showIsland()`（`notify()`）；tickLoop 1s 刷新岛文案。
- `IslandNotifier`：`setShortCriticalText()` + `setRequestPromotedOngoing()`（androidx.core 1.17+）构造 promoted ongoing 通知；`showIsland/updateIsland/hideIsland` 走 notify/cancel。
- `ForegroundDetectorService.isEnabled()` 检查无障碍是否开启；过滤 `miui.systemui.plugin` 等浮层；`getRootInActiveWindow()` 核实前台。
- `TrackedApps.launchableApps()` 用 `<queries>` 枚举可启动应用（免 `QUERY_ALL_PACKAGES`）。
- `UsageReconcileWorker`：每 15min + 「检查并启动」手动触发，把 UsageStats 每日前台时长回填 TimeStore（只取 max、当天活动会话跳过），解决重装后累计继承/漏记。
- UI：主界面顶部「今日合计/本周合计」卡片；应用勾选列表在二级页 `AppListActivity`，每项显示该应用的今日/本周时长。
- `HyperIslandHelper.kt` 里的 `miui.focus.*` key 是示意值，正式联调以官方模板库为准。
- 应用显示名本地化：`values/strings.xml`=`islandTimer`（默认/其他语言），`values-zh-rCN/strings.xml`=`节律岛`（简体中文）。
- 应用图标资源：`mipmap-anydpi-v26/ic_launcher(.round).xml`（adaptive icon，前景/背景矢量 + monochrome），各密度 webp + `ic_launcher-playstore.png`；`AndroidManifest` 使用 `@mipmap/ic_launcher` + `roundIcon`。

另见 `docs/超级岛场景预审材料.md`（第二阶段正式岛申请的场景预审草稿）。

---

## 5. 关键设计决策与边界

1. **先基础岛、后正式岛**：MVP 走零审核的常驻通知上岛路线，验证产品后再申请富样式。
2. **无障碍做"可选增强"**：提高商店过审率（已有"仅 UsageStats 降级"的代码空间）。
3. **空闲降级**：不持续占岛——退桌面即撤通知，符合超级岛"事件驱动"生命周期。
4. **minSdk 26**：`java.time` 无需脱糖；`stopForeground(REMOVE)`、通知渠道直接可用。
5. **息屏即停**：锁屏期间不计时（`ACTION_SCREEN_OFF` 结算撤岛），解锁且被计时应用仍在前台时自动续计，杜绝锁屏虚增。

---

## 6. 构建环境参数

| 项 | 值 |
|---|---|
| AGP | 8.9.2 |
| Kotlin | 2.1.0 |
| Gradle | 8.11.1 |
| compileSdk | 36 |
| targetSdk / minSdk | 34 / 26 |
| 依赖 | core-ktx **1.17.0**（Live Updates API 需要）/ appcompat / material / constraintlayout / recyclerview / lifecycle-runtime-ktx / coroutines / work-runtime-ktx 2.9.1 |
| 本地构建 JDK | **JDK 17**（`E:\Users\hesih\.jdks\jdk-17`） |
| 本地 SDK | `E:\Users\hesih\AppData\Local\Android\Sdk`（local.properties 已写好） |
| 项目已配 gradlew | wrapper（gradle-8.11.1-bin.zip）+ gradle-wrapper.jar |
| 构建状态 | `assembleDebug` / `assembleRelease` 均已验证通过（2026-11-08）；产物见 `app/build/outputs/apk/{debug,release}/` |

> 工具链升级原因：`setRequestPromotedOngoing` 需要 androidx.core 1.17+，而它要求 compileSdk 36 + AGP 8.9.1+（连带 Gradle 8.11.1 + Kotlin 2.1.0）。

打开 `IslandTimer/` 目录即可用 Android Studio 同步构建。真机需 HyperOS 设备才能看到超级岛效果。

---

## 6.1 版本控制 / 仓库

- 远程仓库：https://github.com/ken88666/islandTimer.git
- 分支：`main`（本地 `master` 已改名），本地与远程已同步
- git 身份：`ken88666 <hesihan347@gmail.com>`（仅写在本仓库 `.git/config`，未设全局）
- 认证方式：HTTPS + Git Credential Manager（GCM 2.9.1，浏览器 OAuth）
- `.gitignore` 已补：`/app/build/`、`.kotlin/`、`*.keystore`、`*.jks`、`keystore.properties`

---

## 7. 进度与待办

### 已完成（功能）

- [x] 息屏暂停：监听 `ACTION_SCREEN_OFF` 结束会话，避免锁屏虚增。
- [x] 每周汇总展示：首页今日/本周合计 + 列表项今日/本周。
- [x] UsageStats 对账：WorkManager 15min 周期 + 「检查并启动」手动触发（防抖）。
- [x] 场景预审材料草稿（`docs/超级岛场景预审材料.md`）。
- [x] **基础岛真机验证通过**（HyperOS 3.0.307 国行：上岛 + 稳定常驻 + 防误隐藏 + 息屏暂停）。
- [x] 移除「测试上岛」按钮；应用列表拆到二级页 `AppListActivity`。
- [x] 诊断拆到独立二级页 `DiagnosticsActivity`（面板 + 刷新诊断 + 测试通知按钮；测试通知独立 ID 1002，与正式岛 1001 共存互不覆盖）。

### 已完成（工程化）

- [x] 界面重构：在 Android Studio 手动调整按钮样式、界面层级与外观（主界面 / 应用列表 / 诊断页 + 列表项）。
- [x] git：初始化仓库、修复 `.gitignore`（补 `/app/build/`、`.kotlin/`、keystore 忽略）、首次提交、`master→main`、推送 GitHub。
- [x] README 添加「AI 构建声明」（代码 100% AI 构建 + 人工测试）。
- [x] 命名/包名统一：包名 `com.hyperland.islandtimer` → `com.hsh.islandtimer`（含代码包名）；App 显示名本地化（中文=节律岛，其他语言=islandTimer）；清理「HYPERLAND/」旧名残留。
- [x] 软件图标：SVG 双层 adaptive icon（前景/背景 + `roundIcon` + monochrome + 各密度 webp + Play Store 图），HyperOS 3 经典主题实机验证通过。
- [x] 版本号：`versionName = "1.0.0"`、`versionCode = 10000`（策略 B：major*10000 + minor*100 + patch）。
- [x] 开源许可：添加 MIT `LICENSE`（`Copyright (c) 2026 ken88666`）。
- [x] Release 签名：`app/build.gradle.kts` 启用 `signingConfigs`，从 `keystore.properties` 读取；无 properties 时回退 debug 签名（保证开源可构建）。密钥材料不入库。

### 未完成（发布前准备 · 当前重点）

- [ ] Google Play 上架：Play Console 创建应用、上传 AAB（`bundleRelease`）。
- [ ] 发布前文档收尾：同步 README 工具链版本（README 仍写 AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7，与实际 8.9.2 / 2.1.0 / 8.11.1 不符；另 README 架构图 `canRetrieveWindowContent=false` 与实际 `true` 不一致）。

### 未完成（后续功能 · 低优先级）

- [ ] 通知内容优化：岛文案 `X分XX秒` 可优化为更美观/紧凑展示。
- [ ] 诊断面板：后续可做成隐藏开关（调试用）。
- [ ] 拿到官方 APP_ID 后接入富样式（HyperIsland-ToolKit 或官方模板库联调）。

---

## 下一阶段（新会话从这里接手）

- **当前阶段**：发布前收尾 —— Google Play 上架 + README 工具链同步（命名、图标、版本号、签名、git 已完成）。
- **待办清单**：见 §7「未完成（发布前准备 · 当前重点）」。
- **岛内容的关键约束**（后续优化岛文案时必读）：
  - 岛上只显示 `smallIcon + shortCriticalText`（`shortCriticalText` 就是岛上的那行文字，当前是 `X分XX秒`）；
  - `contentTitle` / `contentText` 只出现在下拉通知栏，**不上岛**；
  - 相关代码在 `island/IslandNotifier.kt` 的 `buildPromotedNotification()` / `buildTimerNotification()`。
- **界面已重构完成**（手动，见 §7）：`MainActivity.kt` + `activity_main.xml`（主界面）、`AppListActivity.kt` + `activity_app_list.xml`（应用列表二级页）、`item_app.xml` + `AppListAdapter.kt`（列表项）、`DiagnosticsActivity.kt` + `activity_diagnostics.xml`（诊断页）。

---

## 8. 参考项目 / 资料地址清单

### 超级岛官方接入
- 中关村在线（接入指南要点）：https://mobile.zol.com.cn/1068/10680884.html
- 凤凰网（开发指南发布）：https://tech.ifeng.com/c/8ngaaVJRPa1
- 新浪科技（开发/接入指南）：https://finance.sina.com.cn/tech/digi/2025-10-23/doc-infuwfyp7043252.shtml

### 上岛实现参考（重点）
- **HyperIsland-ToolKit**（封装 `miui.focus.*` payload 的 Kotlin DSL 库，23+ 模板）：https://github.com/D4vidDf/HyperIsland-ToolKit
  - Maven：`io.github.d4viddf:hyperisland_kit:0.4.5`
- **HyperBridge**（D4vidDf，最关键的 OS3 国行 Live Updates 实证）：https://github.com/D4vidDf/HyperBridge
- **WorkIsland-HyperOS4**（HyperOS 4 实测：AOSP promoted ongoing 上岛 + 保活入口）：https://github.com/Ralsei1337/workisland-hyperos4
- **dsha-island**（HyperOS 3/4 免 root 能力边界）：https://github.com/Ralsei1337/dsha-island
- **WorkIsland-Android**（上游原项目）：https://github.com/STUTTEFRFIX/WorkIsland-Android

### 后台保活参考（重点）
- **掘金《移动智能体的安卓生存法则》（Nyral，ColorOS/HyperOS/MIUI 保活+灵动岛实战）**：https://juejin.cn/post/7702914241569472547
- **Monik**（Shizuku 控制 AppOps/Doze/standby 保活）：https://github.com/vovaxa55/monik
- **AppRetention**（hook 系统 kill 逻辑保活）：https://github.com/HChenX/AppRetention

### Android 16 Live Updates 官方文档
- https://developer.android.google.cn/develop/ui/views/notifications/live-update?hl=en

### 前台应用检测参考
- Flutter `screen_time` 包（UsageStats 封装思路）：https://pub.dev/packages/screen_time
- `screen_time_api`（Android 原生实现）：https://github.com/i4mjad/screen_time_api
