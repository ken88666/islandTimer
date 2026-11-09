# islandTimer · 开发交接（DEV_HANDOFF）

> 本文件是这个仓库的**唯一技术入口**：读完它 + 扫一遍 `app/` 源码，就能无缝接手，不必重新调研平台行为（§2 的事实已经真机验证过，别重复研究）。
>
> | 项 | 值 |
> |---|---|
> | 仓库 | https://github.com/ken88666/islandTimer （分支 `main`） |
> | 应用名 | 简体中文「节律岛」/ 其他语言 `islandTimer` |
> | 包名 | `com.hsh.islandtimer` |
> | 版本 | `versionName 1.0.0` / `versionCode 10000` |
> | 许可 | MIT（`LICENSE`） |
> | 最后同步 | 2026-11-10（`main` 分支头；具体提交以 `git log -1` 为准） |

## 给接手代理的阅读顺序

1. 本文件 §0–§3：产品定义、已查证结论、技术方案；
2. §4 代码地图 → 打开对应 `.kt` 核对实现（**代码是唯一事实来源**，与本文冲突时先信代码再回来改本文）；
3. §6 构建参数 → 立刻跑一次 `assembleDebug` 验证本地环境可用；
4. §7 待办 → 从「当前重点」往下做；
5. 需要平台背景时再查 §8 的链接。

---

## 0. 一句话目标

在小米 **HyperOS「超级岛」** 上做一款"前台应用使用时长计时"工具 App：

- 打开被计时的应用（如抖音）→ 超级岛出现胶囊，从**今日累计时长**开始走；
- 退到桌面 → 停止计时，超级岛消失；
- 下午再打开 → 从上午攒下的时长**继续累计**；
- 支持**每日/每周刷新**、**被计时应用自由配置**；
- 硬约束：**免 root**、**快速响应**、**长时间后台低功耗待机**。

产品定位：本地单机工具，无账号、无服务器、无联网权限、无广告 SDK（隐私政策见 `docs/privacy-policy*.html`）。

---

## 1. 需求原文

> "免 root 实现快速响应地读取前台应用使用并记录连续使用时间、长时间保留后台低功耗待机。比如早上打开抖音，灵动岛从零分钟开始计时，刷一小时退到桌面停止计时、岛消失。下午再打开，岛从上午 1h 处开始继续计时。计时可设每天刷新/每周刷新，被计时软件自由配置。"

---

## 2. 研究结论（已查证，勿重复调研）

### 2.1 小米「超级岛」分两档

| 档位 | 机制 | 是否需官方白名单 | 与需求契合度 |
|---|---|---|---|
| **基础岛** | **Android 16「Live Updates / promoted ongoing」通知**：`NotificationCompat.Builder.setShortCriticalText()` + `.setRequestPromotedOngoing(true)`，系统显示 `smallIcon + shortCriticalText` 胶囊 | **不需要**（与签名无关，debug 包即可） | 100%，当前实现走这条 |
| **正式超级岛** | 通过 `miui.focus.param` 解锁大岛/小岛/图片/进度条等 23+ 模板 | **需要**（申请 + 场景预审 + 指纹证书 + 灰度） | 部分契合，有 3 个审核风险点（见 §2.3） |

> 真机验证基准（2026-11-04，HyperOS 3.0.307 国行）：
> - 基础岛走的是 **Android 16 原生 Live Updates（promoted ongoing）**，不是「任意常驻通知」；
> - 必须真正调用 `setShortCriticalText()` + `setRequestPromotedOngoing()`（androidx.core 1.17+）；**raw extras / 反射改不到 Notification 内部字段**，否则 `hasPromotableCharacteristics()` 恒为 false；
> - manifest 需声明 `POST_PROMOTED_NOTIFICATIONS`；
> - 用户需在本应用通知设置里开启「焦点通知 / 实时活动」开关；
> - 官方 `miui.focus.param` 未鉴权时会被系统静默忽略。

### 2.2 官方超级岛接入流程（第二阶段才用）

1. 注册小米开发者账号 → 创建并上架应用；
2. 开发者后台选目标应用 → **申请开通超级岛** → 配置**指纹证书** → 提交**上岛场景预审材料**；
3. 审核通过 → 开发联调 → 申请**设备白名单**自测 → 提交正式 APK；
4. 审核通过获正式权限 → **灰度发布 7~15 天后全量**。

两种通道：客户端原生通知 / MIPUSH（`regId`，焦点通知）。
资源规范：单图 ≤100KB、HTTPS、宽高比 1.78:1(16:9) ~ 1:1；样式须遵循官方模板库。
预审材料草稿：`docs/超级岛场景预审材料.md`。

### 2.3 上正式岛的三个审核风险点

1. **常驻时长边界**：用户刷 1h 抖音岛就挂 1h，可能被判定"常驻占岛"。对策：进应用大岛显示几秒 → 折叠成胶囊只显示 `⏱ 12:34`；明确"仅被计时应用前台时出现，退出即消失"。
2. **无障碍权限正当性**：商店审核会盘问无障碍用途。对策：写明"只用于检测前台应用统计时长，不读内容、不模拟操作"，并把无障碍做成可选增强（保留"仅 UsageStats"降级模式）。
3. **指纹证书稳定性**：正式岛靠"指纹证书 + APP_ID"鉴权，必须从第一天就用正式签名（`app/build.gradle.kts` 已配 `signingConfigs.release`）。

---

## 3. 技术方案

### 3.1 免 root 快速前台检测 = 双通道

| 方案 | 响应 | 权威性 | 免 root | 结论 |
|---|---|---|---|---|
| `getRunningTasks` | — | — | 已废弃 | ❌ |
| 读 `/proc/*/cmdline` | 快 | 准 | **需 root** | ❌ |
| `UsageStatsManager` 轮询 | 秒~分钟级延迟 | 权威、可补历史 | ✅ | 做**账本/校准** |
| **AccessibilityService** 窗口事件 | **亚秒级** | 准、实时 | ✅ | 做**触发层** |

**结论：无障碍（触发）+ UsageStats（账本）双通道，是免 root 下唯一同时满足"快速响应"和"时长准确"的组合。**

- 无障碍监听 `TYPE_WINDOW_STATE_CHANGED`；`canRetrieveWindowContent="true"` —— 只为 hide 前用 `getRootInActiveWindow()` 核实真实前台包名，不读文字内容。
- 桌面包名 `com.miui.home`（另有 `contains("launcher")` 兜底）= 退桌面判断依据。
- 过滤系统浮层包：`miui.systemui.plugin`、`com.miui.systemui`、`com.android.quicksearchbox` 等（下拉通知栏/搜索框会误触发「切应用」）。
- UsageStats 用 `MOVE_TO_FOREGROUND/BACKGROUND`（API 29+），老版本用 `ACTIVITY_RESUMED/PAUSED`；`INTERVAL_DAILY/WEEKLY` 天然适配每日/每周刷新。

### 3.2 连续时长状态机（核心语义）

两个量：

- `todayAccumulatedMs`：今日已累计（持久化在 `TimeStore`，key = `日期|包名`）；
- `sessionStartRealtime`：本次会话起点（内存态 `elapsedRealtime`）。

**展示值 = `todayAccumulatedMs + (elapsedRealtime - sessionStartRealtime)`**

流程：进被计时应用 → 记 `sessionStart`、读 `todayAccumulated` → 岛出现；退桌面 → 结算 `todayAccumulated += (now - sessionStart)`、岛消失；下午重开 → `todayAccumulated` 已是上午的 1h，从 1h 续走。

### 3.3 后台保活与上岛通知分离（关键踩坑）

1. **岛通知必须走 `notify()`，不能走 `startForeground()`**：前台服务通知在 HyperOS 3 上会被挤下岛/不稳定；`notify()` 发的 promoted 通知稳定。
2. **前台服务只做保活**：`startForeground()` 用一条最小化通知（与岛通知不同 ID），两条互不覆盖。
3. **保活优先级（真机实测）**：IME ≥ 悬浮窗 > 前台服务（带可见通知）> 无障碍 > 普通后台。前台服务渠道用 `IMPORTANCE_MIN` 会被 HyperOS 当「不可见」进而冻结/杀进程。
4. **HyperOS 保活四件套**：忽略电池优化 / MIUI 自启动 / 省电策略=无限制 / 最近任务锁定（App 内有入口）。
5. **低功耗**：事件驱动 + 1s 心跳刷新岛文案（时长精确到秒）；空闲 `stopForeground(REMOVE)` + `notify().cancel()`；不用 `PARTIAL_WAKE_LOCK`。

### 3.4 防误隐藏 / 息屏（两个必读边界）

**防误隐藏**：启动动画/闪屏/下拉通知栏/搜索框会产生瞬态「桌面/非被计时应用」窗口事件，导致 show→hide 闪岛甚至掉岛。解法（`AppForegroundTracker` 内）：

- 过滤系统浮层包；
- hide 加 `HIDE_DEBOUNCE_MS = 500` 防抖 + show 后 `SHOW_GRACE_MS = 2000` 宽限期；
- hide 真正执行前用 `getRootInActiveWindow()` 核实真实前台，误判则取消隐藏。

**息屏必须无条件隐藏**：`onScreenOff()` 不能走「核实前台」——息屏瞬间 `getRootInActiveWindow()` 返回的还是原应用，会被误判成「仍在前台」而取消隐藏，导致岛卡在旧应用上。所以 `settleAndHide(verify)` 里，息屏路径用 `verify = false` 直接结算撤岛，只有防抖隐藏才做核实。解锁后若被计时应用仍在前台，自动续计。

---

## 4. 代码地图（对应 HEAD，`app/src/main/java/com/hsh/islandtimer/`）

```
App.kt                          # Application：初始化单例 + 调度对账 Worker
MainActivity.kt                 # 主界面：权限/保活/今日周合计/诊断入口（更多菜单）+ 检查启动（按钮切「加载中」+ Snackbar 防连点）
AppListActivity.kt              # 二级页：选择要计时的应用（搜索/已勾选置顶）
DiagnosticsActivity.kt          # 二级页：诊断面板 + 刷新诊断 + 常驻测试通知
core/
  AppForegroundTracker.kt       # ★ 状态机：连续时长/今日累计/息屏暂停/防误隐藏
  TimeStore.kt                  # 按天分桶 → 每日/每周刷新（getWeekMs）
  TrackedApps.kt                # 被计时应用集合 + 枚举可启动应用（labelFor）
  UsageStatsLedger.kt           # UsageStats 权威账本（getDayForegroundMs）
  UsageReconcileWorker.kt       # 账本对账：15min 周期 + reconcileNow() 手动触发
service/
  ForegroundDetectorService.kt  # 无障碍：前台检测 + 保活锚点 + 息屏广播 + 浮层过滤 + 前台核实
  UsageTimerService.kt          # 前台服务：最小化保活通知（ID 1003）+ 1s 心跳（岛通知走 notify，ID 1001）
keepalive/
  KeepAliveHelper.kt            # MIUI 自启动/电池优化/权限跳转
island/
  IslandNotifier.kt             # 基础岛通知构造（notify 通道 + promoted 标记，ID 1001 岛 / 1002 测试；服务保活通知占 1003）
  IslandDiagnostics.kt          # 无 adb 诊断（extras 回读/能力查询/活跃通知）
  HyperIslandHelper.kt          # 富样式 miui.focus（可选，需鉴权；key 为示意值）
ui/
  AppListAdapter.kt             # 列表项（含「今日 · 本周」时长）
```

资源侧要点：

- `res/xml/accessibility_config.xml`：`typeWindowStateChanged` + `canRetrieveWindowContent="true"` + `notificationTimeout=100`；
- `AndroidManifest.xml`：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`POST_PROMOTED_NOTIFICATIONS`、`PACKAGE_USAGE_STATS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；`<queries>` 枚举可启动应用（避免 `QUERY_ALL_PACKAGES`）；FGS 用 `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`；
- 应用名：`values/strings.xml` = `islandTimer`，`values-zh-rCN/strings.xml` = `节律岛`；
- 图标：`mipmap-anydpi-v26/ic_launcher(.round).xml`（adaptive，前景/背景矢量 + monochrome）+ 各密度 webp + `ic_launcher-playstore.png`；
- 通知渠道：`island_timer`，`IMPORTANCE_DEFAULT`（**不能**用 MIN，见 §3.3）。

关键实现点备忘：

- 岛通知只上 `smallIcon + shortCriticalText`；`contentTitle` / `contentText` **不上岛**，只出现在下拉通知栏 → 改文案只改 `IslandNotifier.buildPromotedNotification()` 的 `shortCriticalText`；
- `IslandNotifier.format()`：≥1h → `X小时XX分`；≥1min → `X分XX秒`；否则 `X秒`；
- `UsageReconcileWorker`：每 15min + 「检查并启动」手动触发，把 UsageStats 每日前台时长回填 `TimeStore`（只取 max、当天活动会话跳过），解决重装后累计继承/漏记；
- `HyperIslandHelper.kt` 里的 `miui.focus.*` key 是示意值，正式联调以官方模板库为准；依赖 `io.github.d4viddf:hyperisland_kit` 在 `app/build.gradle.kts` 里是注释状态。

---

## 5. 关键设计决策与边界

1. **先基础岛、后正式岛**：MVP 走零审核的 promoted ongoing 路线，验证产品后再申请富样式。
2. **无障碍做"可选增强"**：提高商店过审率（已有"仅 UsageStats 降级"的代码空间）。
3. **空闲降级**：不持续占岛——退桌面即撤通知，符合超级岛"事件驱动"生命周期。
4. **minSdk 26**：`java.time` 无需脱糖；`stopForeground(REMOVE)`、通知渠道直接可用。
5. **息屏即停**：锁屏期间不计时（`ACTION_SCREEN_OFF` 结算撤岛），解锁且被计时应用仍在前台时自动续计，杜绝锁屏虚增。
6. **签名可回退**：`app/build.gradle.kts` 里 `keystore.properties` 存在 → 用正式签名；不存在 → 回退 debug 签名，保证 clone 后直接可构建。密钥材料不入库。

---

## 6. 构建环境参数

| 项 | 值 |
|---|---|
| AGP | 8.9.2 |
| Kotlin | 2.1.0 |
| Gradle | 8.11.1（wrapper `gradle-8.11.1-bin.zip`） |
| compileSdk | 36 |
| targetSdk / minSdk | 36 / 26 |
| Java / jvmTarget | 17（构建用 JDK 21） |
| 依赖 | core-ktx **1.17.0**（Live Updates API）/ appcompat 1.7.0 / material 1.12.0 / constraintlayout 2.1.4 / recyclerview 1.3.2 / lifecycle-runtime-ktx 2.8.3 / coroutines-android 1.8.1 / work-runtime-ktx 2.9.1 |
| 构建用 JDK | JBR 21（`<用户目录>\.jdks\jbr-21.0.11`，Android Studio 自带）；备选 Temurin 17.0.21。**系统 PATH 里的 Oracle Java 8 会导致 `Dependency requires at least JVM runtime version 11`** |
| Android SDK | 由 `local.properties` 的 `sdk.dir` 指向本机 SDK 根目录（不入库）；build-tools 34.0.0 / 35.0.0 / 36.0.0 已装 |
| 构建状态 | `assembleDebug` / `assembleRelease` / `bundleRelease` 均已验证通过；release 产物在 `app/build/outputs/apk/release/`（APK）与 `bundle/release/`（AAB）。**2026-11-09 本机（迁移后）复验**：`JAVA_HOME` 指 JBR 21 时 `assembleDebug` BUILD SUCCESSFUL |

```powershell
# Windows：先指定 JDK 17+，再构建（PATH 里的 Oracle Java 8 不可用）
$env:JAVA_HOME = "<用户目录>\.jdks\jbr-21.0.11"   # 或任意 JDK 17/21
.\gradlew.bat assembleDebug
```

> 工具链升级原因：`setRequestPromotedOngoing` 需要 androidx.core 1.17+，而它要求 compileSdk 36 + AGP 8.9.1+（连带 Gradle 8.11.1 + Kotlin 2.1.0）。

---

## 7. 状态与待办

### 已完成（功能）

- [x] 连续时长状态机（上午攒 / 下午续计）+ 息屏暂停（`ACTION_SCREEN_OFF` 结算，避免锁屏虚增）。
- [x] 每日 / 每周汇总：首页「今日合计 / 本周合计」+ 列表项「今日 · 本周」。
- [x] UsageStats 对账：WorkManager 15min 周期 + 手动触发（防连点）。
- [x] 防误隐藏（浮层过滤 + 500ms 防抖 + 2s 宽限 + 前台核实）。
- [x] **基础岛真机验证通过**（HyperOS 3.0.307 国行：上岛 + 稳定常驻 + 防误隐藏 + 息屏暂停）。
- [x] 应用列表拆二级页 `AppListActivity`；诊断拆独立页 `DiagnosticsActivity`（测试通知独立 ID 1002，与正式岛 1001 共存）。
- [x] 界面重构（主界面 / 应用列表 / 诊断页 + 列表项，人工调样式与层级）。

### 已完成（工程化）

- [x] 包名 `com.hyperland.islandtimer` → `com.hsh.islandtimer`；应用名本地化；清理旧名残留。
- [x] 图标：SVG 双层 adaptive icon（前景/背景 + `roundIcon` + monochrome + 各密度 webp + Play 图标），HyperOS 3 经典主题实机验证。
- [x] 版本号 `1.0.0 / 10000`（策略：major*10000 + minor*100 + patch）。
- [x] MIT `LICENSE`。
- [x] release `signingConfigs`（从 `keystore.properties` 读取，缺失回退 debug）。
- [x] git 仓库 + `.gitignore`（`/app/build/`、`.kotlin/`、`*.keystore`、`*.jks`、`keystore.properties`）+ README「AI 构建声明」。
- [x] `targetSdk 36`（满足 Google Play 新提交要求）。
- [x] 隐私政策页（`docs/index.html` + `privacy-policy.html` / `privacy-policy-zh.html`，GitHub Pages 已生效：https://ken88666.github.io/islandTimer/ ）。维护注意：Pages 源是 `/docs` + `.nojekyll`（纯 HTML 直出）；`.md` 只是文案草稿，改政策必须同步 HTML，否则线上不变；`privacy-policy.html` 的 URL 是 Play Console 里填的地址，路径别动。

### 未完成（当前重点）

- [x] **README 与代码事实同步**（2026-11-09 完成，逐条结果）：
  - 工具链：AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 → **8.9.2 / 2.1.0 / 8.11.1**，并补依赖版本表；
  - SDK：compileSdk/targetSdk 34 → **36 / 36**；
  - 架构图：`canRetrieveWindowContent=false` → **true**，并写明用途是"hide 前核实真实前台包名、不读文字内容"；
  - 刷新频率：「15s 节流」→ **`UsageTimerService.TICK_MS = 1s` 心跳**，同时注明岛通知走 `notify()`；
  - 目录结构：补 `AppListActivity` / `DiagnosticsActivity` / `UsageReconcileWorker` / `IslandDiagnostics`，并补 `docs/` 隐私政策、`release.keystore` + `keystore.properties` 签名说明、APK/AAB 产物路径、`JAVA_HOME` 要求；
  - 首屏表述：「HyperOS 4 才会抬 AOSP 常驻通知」→ 按 §2.1 实测校正为 **HyperOS 3.0.307 已验证的 Android 16 Live Updates（promoted ongoing）**，并补 `POST_PROMOTED_NOTIFICATIONS` 与用户侧「焦点通知」开关；
  - 附带补：架构图里 `UsageTimerService` 的定位由"基础岛载体"改为"保活 + 1s 心跳"，末端新增 `IslandNotifier` 载体；"已知边界"里息屏条目改为已实现（`ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT`）；补隐私政策链接与 MIT 许可段。
- [ ] 商店发布准备（素材与文案部分）：真机截图（主界面 / 应用列表 / 诊断页）、完整说明与简短说明文案、置顶大图。

### 已发现但未修（接手先处理，成本低）

> 2026-11-09 本机（S5）三条已全部修掉，保留原文备查。

- [x] **通知 ID 冲突**（已修）：`UsageTimerService.SERVICE_NOTIFICATION_ID` 1002 → **1003**，与 `IslandNotifier.TEST_NOTIFICATION_ID = 1002`、`NOTIFICATION_ID = 1001` 三者互不覆盖；`isTestIslandShowing()` 不会再误判服务通知。
- [x] `UsageTimerService` 类头 KDoc 同步（已修）：改为"服务通知 ID=1003，渠道 `IMPORTANCE_DEFAULT`（MIN 会被 HyperOS 视作不可见而冻结进程）"，并在 companion 里补 1001/1002/1003 的用途注释。
- [x] **死资源**（已删）：`res/drawable/ic_loading_spinner.xml`、`ic_loading_spinner_anim.xml`、`res/animator/spinner_rotate.xml` 三件套删除（删除前全仓检索确认仅三者互相引用，无代码/布局引用）；`ic_more_vert` / `ic_chevron_right` 保留未动。

### 未完成（后续功能 · 低优先级）

- [ ] **R8/ProGuard 混淆**（1.0.0 稳定后再做，避免发布冲刺期引入新变量）：`isMinifyEnabled = true` + `isShrinkResources = true`；全量人工回归（防反射/动态加载出问题）；`bundleRelease` 生成 `mapping.txt`，上传时在 Play Console 关联 deobfuscation file。
- [ ] 岛文案优化：`X分XX秒` 可换成更紧凑/美观的展示（注意 §4 的"只上 shortCriticalText"约束）。
- [ ] 诊断面板做成隐藏开关（调试用）。
- [ ] 拿到官方 APP_ID 后接入富样式（HyperIsland-ToolKit 或官方模板库联调）。
- [ ] 回归清单：应用列表变更、每日/每周边界跨零点、息屏/解锁、下拉通知栏、来回快速切应用。

---

## 8. 参考项目 / 资料

**超级岛官方接入**

- 中关村在线（接入指南要点）：https://mobile.zol.com.cn/1068/10680884.html
- 凤凰网（开发指南发布）：https://tech.ifeng.com/c/8ngaaVJRPa1
- 新浪科技（开发/接入指南）：https://finance.sina.com.cn/tech/digi/2025-10-23/doc-infuwfyp7043252.shtml
- Android 16 Live Updates 官方文档：https://developer.android.google.cn/develop/ui/views/notifications/live-update?hl=en

**上岛实现参考（重点）**

- **HyperIsland-ToolKit**（`miui.focus.*` payload Kotlin DSL，23+ 模板）：https://github.com/D4vidDf/HyperIsland-ToolKit ｜ Maven `io.github.d4viddf:hyperisland_kit:0.4.5`
- **HyperBridge**（OS3 国行 Live Updates 实证，最关键的参考）：https://github.com/D4vidDf/HyperBridge
- **WorkIsland-HyperOS4**（HyperOS 4：AOSP promoted ongoing 上岛 + 保活入口）：https://github.com/Ralsei1337/workisland-hyperos4
- **dsha-island**（HyperOS 3/4 免 root 能力边界）：https://github.com/Ralsei1337/dsha-island
- **WorkIsland-Android**（上游原项目）：https://github.com/STUTTEFRFIX/WorkIsland-Android

**后台保活参考**

- 掘金《移动智能体的安卓生存法则》（ColorOS/HyperOS/MIUI 保活 + 灵动岛实战）：https://juejin.cn/post/7702914241569472547
- **Monik**（Shizuku 控制 AppOps/Doze/standby 保活）：https://github.com/vovaxa55/monik
- **AppRetention**（hook 系统 kill 逻辑保活）：https://github.com/HChenX/AppRetention

**前台应用检测参考**

- Flutter `screen_time` 包（UsageStats 封装思路）：https://pub.dev/packages/screen_time
- `screen_time_api`（Android 原生实现）：https://github.com/i4mjad/screen_time_api

---

## 9. 维护规则（改本文件前先读）

1. **代码优先**：任何与本文件冲突的描述，以 `app/` 当前代码为准，然后回来改本文件。
2. **只增改不删**：删段落只在"某功能已被移除"时做，并在 §10 记一条。
3. **不许写进本文件**：密钥/口令/私钥材料、个人邮箱与账号、发布运营数据、渠道商务判断。本文件是公开仓库的一部分。
4. 每次改动都在 §10 追加一行：`日期 · 改了哪节 · 为什么`。
5. 发布前复查：README、本文件、`docs/` 三者描述需一致。

## 10. 变更日志

- 2026-11-08 · 初版（全量功能 + 工程化 + 研究结论）。
- 2026-11-09 · 重构为 `DEV_HANDOFF.md`：重排阅读顺序、补 `docs/` 隐私政策与 `targetSdk 36`、修正代码地图与构建参数、README 不一致项细化为可执行清单、补 §9 维护规则。
- 2026-11-09 · S1（技术线评审）：核对 §3/§4/§6/§7 与代码一致；§6 构建命令去标识化本地用户名（避免公开泄露 Windows 用户名）。
- 2026-11-09 · S2（UI/交互线评审）：§4 主界面「转圈防连点」→「加载中 + Snackbar」（对齐 `MainActivity.kt`）；头部「最后同步」改为按 `git log -1` 取值；核对 §3 常量、§6 工具链依赖、16 个 `.kt` 均与代码一致。
- 2026-11-09 · S3（工程化/记忆线评审）：§6 构建状态补 `bundleRelease` 与 release 产物路径（APK/AAB 实测存在，2026-11-08）；核对 §3 常量与 §4 代码地图（16 个 `.kt`）与代码一致。
- 2026-11-09 · S4（发布线评审）：§7 隐私政策条目补 GitHub Pages 维护提示（`/docs` + `.nojekyll` 纯 HTML 直出，`.md` 为草稿、改政策须同步 HTML）；核对 §6 工具链（AGP 8.9.2 / Kotlin 2.1.0 / Gradle 8.11.1 / targetSdk 36）与 `app/build.gradle.kts`、`gradle-wrapper.properties` 一致。
- 2026-11-09 · S0 汇总复核：合并四份评审、落定会话编号 S1–S4；对四份评审的交叉质疑逐条实测裁定——JDK 17 备选（Temurin 17.0.21）确实存在、`app/build/outputs/{apk,bundle}/release` 产物确实存在、`.kt` 实际 16 个；并新增「死资源」一条待修项（spinner 三件套已无引用）。
- 2026-11-09 · S5（迁移后本机接续 · P3）：① §6 换机复验并改写——JDK 路径去标识化保留占位、补「系统 PATH 的 Oracle Java 8 会报 `Dependency requires at least JVM runtime version 11`」、补 SDK 由 `local.properties` 指向与 build-tools 34/35/36、补本机 `assembleDebug` BUILD SUCCESSFUL；② §7「README 与代码事实同步」六项全部改完并逐条留证（工具链/SDK/`canRetrieveWindowContent`/1s 心跳/目录结构/首屏 Live Updates 表述）；③ §7「已发现但未修」三条全部修掉：服务通知 ID 1002→1003、`UsageTimerService` 类头 KDoc 同步、删除 spinner 死资源三件套；④ §4 代码地图补通知 ID 分配（1001 岛 / 1002 测试 / 1003 服务）。头部「最后同步」改为按 `git log -1` 取值，不再写死提交号。
