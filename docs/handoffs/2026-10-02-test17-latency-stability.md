# test.17 延迟/稳定性止血包：工作交接

日期：2026-10-02 ~ 2026-10-03（Asia/Shanghai）
作者：WorkBuddy agent 会话（交接文档，供人或其他 agent 接手）

## 一句话现状

本地分支 `feat/test17-latency-stability`（基于 `fix/android-test15-stability` @ `54a416f`）已完成 6 笔提交，`compileDebugKotlin` + `testDebugUnitTest` 全绿，MuMu 模拟器设置界面 UI 验收全绿；**尚未推送 GitHub，真机验收未做**。验收通过后：本地合并 `main` → 打 tag → 最后才 push（用户明确要求的工作流）。

## 1. 背景

用户报告四个问题并要求修复（长期目标：无损传输 + 尽可能低延迟 + 稳定；工作方式：分支开发、本地测试完再传 GitHub）：

1. 连接时提示音有时连续响两声
2. 延迟设置：要标注是接收端还是发送端、要能直接输入数值（例如把 41ms 改成 40ms）、滑块挡位固定每档 21ms 要改无级
3. 接收端锁屏放着、发送端无音频；之后发送端开播，接收端无声；解锁接收端后又自动连上（test.16，第一天无此问题）
4. 20ms 延迟时接收播放链路被连续 underrun 拖死（`docs/PROJECT_STATE.md` 与 `docs/handoffs/2026-10-01-device-debug.md` 有实测记载）

## 2. 诊断结论（代码级根因）

### ① 提示音双响
- 连接音（`R.raw.connection_sound`，MD5 `d990bd5f…`）与断连音（`disconnection_sound`，MD5 `5e034955…`）是**两段不同的音频**（同为 192078 字节 ≈2.0s），用户听到的是**两次连接音**。
- 连接音在每次会话建立成功时必播（单播与组播两条路径各一处）；断连音的全部 5 处调用点都带 `!ClientSessionController.wantsConnection()` 门控 → **自动重连期间断连音被抑制**；重连退避最短 1s。
- 结论：双响 = 真实的"快速断线 → 1s 后重连成功"，第二声来自重连成功。声音是症状，底层断线与问题③同源。

### ② 延迟滑块 21ms 档位 bug
- `Composables.kt` 中 `range = 20f..400f` 但 `steps = ((400f - 40f) / 20f).toInt() - 1 = 17`（按旧 40ms 起点计算）→ 18 等分 → 步长 (400−20)/18 = **21.1ms**，网格 20/41/62/83…，40ms 永远调不可达。与用户观察完全吻合。

### ③ 锁屏断连、解锁自动重连
- `ClientService` 已持有 PARTIAL_WAKE_LOCK（`wfas:client`）+ `WIFI_MODE_FULL_LOW_LATENCY` WifiLock，AOSP 语义下锁屏不应断。
- 但 HyperOS/MIUI 进程冻结（cgroup freezer）可无视 wakelock；`docs/handoffs/2026-10-01-device-debug.md` 有实证（07:17 HyperOS `AutoPowerKill`、`RUN_ANY_IN_BACKGROUND=ignore`）。
- 因果链：锁屏 → 冻结 → PING 停发/UDP 停收/重连协程停摆 → 解锁解冻 → 30s 服务器活动看门狗立即判死 → `wantsConnection` 仍 true → 1s 退避重连成功 → "刚解锁就自动连上"。
- "第一天没问题"无法从代码证实（使用场景差异或 OEM 省电策略学习期），需现场日志定罪。
- 代码层未修冻结本身（属 test.18：电池豁免引导等）；本分支已修"断线后的恢复体验"（重连静音）并为恢复铺路（看门狗）。

### ④ 20ms underrun 拖死播放链路
- 目标 20ms + 预热 10ms，对实测 jitter 9–10ms / 峰值 29.33ms 余量不足 → 缓冲频繁打空（25 秒 +36,875 underrun；长跑累计 277 万、轨道 inactive 但 AudioPolicy 仍 started）。
- `PlayoutGovernor.retune()` 播放速率修正钳在 ±0.8%，追不上 ±50% 级缓冲误差；`shouldDrop()` 只治上溢。
- stall 恢复只写在 `writePcm()` 内 → 收包路径自己停摆时（UDP 队列 17 秒无消费）恢复逻辑一起死；OEM 禁用轨道后 pause/flush/play 不保证复活；无独立哨兵。
- 异常安全（已核实）：`startClient` 的公共尾部有 `catch`（`markDisconnect("CLIENT_EXCEPTION_*")`）+ `finally`（必调 `onServerDisconnected`），`writePcm` 的 `check(written >= 0)` 抛异常不会永久卡死也不会崩进程，但会把"轨道死亡"升级成整会话重建（test.18 改为原地重建）。

## 3. 已完成改动（分支上 6 笔提交，全部本地未推送）

| 提交 | 内容 |
|---|---|
| `cd99120` | 延迟设置改造：预设下拉（自适应/极速20/均衡40/稳定60）+ 无级滑块（`steps = 0`，修 21ms 档位 bug）+ 点数值弹输入框（钳制 20–400ms，非法输入红字提示）+ 三要点标注。改动：`Composables.kt`（`SettingsSliderItem` 新增可选 `onValueClick`；设置块重写）、`data/SettingsDataStore.kt`（新装默认 `latencyMs` 20→40，`saveAdvancedAudio` 钳制 20–400）、`values/strings.xml` + `values-zh-rCN/strings.xml` |
| `df83462` | 接收端看门狗 + 预热联动 + 重连静音：单播 watchdog 每秒采样 `AudioTrack.underrunCount`（minSdk 24 直用）与 `PlayoutGovernor.stalledForMs()`；underrun 风暴（>200/s×3s 且音频在流）或播放头停滞 5s 带队列 → 置 `rebuildRequested` 标志，接收循环**原地重建 AudioTrack**（unregister→release→`trackBuilder.build()`→新 governor→重新预热→swap）；请求 5s 未被消费 → `markDisconnect("PLAYBACK_WATCHDOG_UNACKNOWLEDGED")` 走正常断连重连。预热从固定 10ms 改为 **target/2**（单播+组播）。`startClient` 新参 `announceConnectSound`，`ClientSessionController.startAttempt` 传 `reconnectAttempt == 0` → 重连成功不再重播全音效。改动：`NetworkManager.kt`、`PlayoutGovernor.kt`（新增 `stalledForMs()`）、`ClientSessionController.kt` |
| `f84b0e1` | `docs/PROJECT_STATE.md` 记录 test.17 变更与新默认值 |
| `1ef02f8` | 自适应预设：新偏好 `adaptiveLatency`（默认 true，新装默认挡）走完整接线（DataStore→`MainViewModel.setAdaptiveLatency`→`MainActivity`→`ExpressiveSettingsScreen`→`SettingsScreenContent`）；`PlayoutGovernor` 增加 `adaptive` 参数（构造器末位带默认，现有 7 参测试兼容）+ 20–80ms 目标带（floor/ceiling）+ `retargetMs(delta)` / `targetMs()`；watchdog 自适应驱动：underrun>100/s×2s → **+10ms**（上限 80），干净 30s → **−5ms** 回落，头部停滞 5s 仍走重建；自适应下 AudioTrack 缓冲按 80ms 上限 + 140ms 余量规划（固定模式仍 target+80）；任何手动改值（滑块/输入框/固定挡位）自动关自适应并在滑块描述显示提示。组播路径同步支持（`mcAdaptive`）。 |
| `3687080` | 删除 `values-it/`（上游 Marco Morosi 意大利语遗留；产品只有中英，用户确认不要意大利语），PROJECT_STATE 补记 |
| `9294d35` | `app/build.gradle.kts`：debug 构建加 `applicationIdSuffix = ".debug"` + `versionNameSuffix = "-localdebug"` → 本地验收包 `io.github.mu23xr.audiobridge.debug` 与正式签名包**共存安装**，不占用/不破坏 test.16（provider authority 是 `${applicationId}.shizuku`，无冲突） |

### 关键新 API / 偏好（接手者速查）

- `PlayoutGovernor`：`stalledForMs()`（队列非空且播放头停滞毫秒数）、`retargetMs(delta): Boolean`（带内移动目标）、`targetMs()`、构造参数 `adaptive: Boolean = false`（末位，默认兼容旧调用）
- `NetworkManager.startClient` 新参数：`adaptiveLatency: Boolean = false`、`announceConnectSound: Boolean = true`（均带默认，旧调用兼容）
- 偏好键：`ADAPTIVE_LATENCY`（boolean，默认 true）；`LATENCY_MS` 默认 40；`saveAdaptiveLatency(enabled)`

## 4. 环境修复（影响本机所有构建，务必知晓）

- **代理污染实测**：`services.gradle.org` 的 gradle-8.13-bin.zip 下载被注入假内容（SHA256 与官方 `20f1b117…` 不符）；`repo.maven.apache.org` / `plugins.gradle.org` 依赖解析全部 403。
- 对策：新建 `D:/apps/Dev/gradle-home/init.d/mirrors.init.gradle`，把**阿里云镜像**（google/public/gradle-plugin）**前置**到 pluginManagement 与 dependencyResolutionManagement（官方仓库保留兜底）。该 init 脚本作用于整个 gradle-home（用户的"方案A：跟工具走"目录）。
- Gradle 8.13 发行版从腾讯镜像下载（`https://mirrors.cloud.tencent.com/gradle/gradle-8.13-bin.zip`，SHA256 校验通过）后手工播种到 `gradle-home/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/`。
- 标准构建命令（无 local.properties，靠环境变量；JAVA 21 可用；无 flavor）：
  ```bash
  cd D:/project/app/AudioBridge/source/android
  ANDROID_HOME="D:/apps/Dev/android-sdk" GRADLE_USER_HOME="D:/apps/Dev/gradle-home" \
    ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain --no-daemon
  ```
- gradle-home `.tmp` 偶发"拒绝访问"（AV 锁临时文件），重跑即可。

## 5. 已完成验证

- `:app:compileDebugKotlin` ✅、`:app:testDebugUnitTest` ✅（含 `AudioSilenceRegressionTest`；仅历史遗留 deprecation 警告，与本次改动无关）
- **MuMu 模拟器 UI 验收全绿**（实例 0，横屏 **1600×900** @240dpi，Android 12）：
  - 安装 `io.github.mu23xr.audiobridge.debug` 成功，启动正常，版本号显示 `1.2.1-localdebug`
  - 延迟预设下拉四挡齐全，**自适应默认选中**；选"均衡(40ms)"→自适应关闭、滑块提示消失
  - 点数值弹输入框 → 输入 37ms → 显示"自定义：37 ms"（精确值生效）
  - 滑块拖到 196ms（旧 21ms 网格不可能的值，无级生效）
  - "播放缓冲（接收端）+ 不会同步到发送端"标注正确；恢复自适应后提示回归
  - 截图存于 `C:/Users/w1741107948/Documents/MuMu共享文件夹/ab_*.png`（ab_home/ab_s1–s5/ab_set1–2/ab_menu/ab_bal/ab_dlg/ab_37/ab_slide/ab_final）
- 模拟器覆盖不了（必须真机）：双响音、锁屏断连、underrun 真实抖动——分别依赖真实 Wi-Fi 瞬断、HyperOS 冻结、真实无线抖动。

## 6. 待办（按优先级）

1. **真机验收**（平板发送 → 手机接收，装 `.debug` 共存包，先在手机系统设置里把该包省电策略设为"无限制"+允许自启动——HyperOS 冻结的应用内引导页属 test.18）。清单：
   - 双响音：正常播放中**关发送端 Wi-Fi 5 秒再开** → 期望断线无声、重连成功无声（声音直接恢复）；logcat 有 `[CLIENT][DISCONNECT] reason=…`
   - 锁屏 2a：播放中锁屏 30–60 分钟 → 持续出声；logcat 若有 `[PLAYOUT][ADAPTIVE] target -> …` 说明抖动被自适应吸收
   - 锁屏 2b：锁屏空闲 10–15 分钟后发送端开播 → 不解锁应几秒内出声；若必须解锁才恢复，logcat 查 `PowerKeeper`/`autoKill`（即 test.18 要治的冻结）
   - underrun：播放中把手机远离路由器 → 听爆音减少 + logcat `[PLAYOUT][ADAPTIVE]` 爬升；回位 30s 后回落
   - 长挂机：再出现无声**不需要手动重启应用**，logcat 必有 `[PLAYOUT][WATCHDOG]` 或 `reason=PLAYBACK_WATCHDOG_UNACKNOWLEDGED` 并自动恢复
   - logcat 采集：`adb logcat -c && adb logcat -v time | grep -E "\[CLIENT\]\[DISCONNECT\]|SESSION-END|\[PLAYOUT\]|PowerKeeper"`
2. 验收全绿 → **本地合并 main → 打 tag（如 v1.3.1-test.17）→ push main + tag**；随后按 AGENTS.md 补 GitHub 记录：更新 Issue #4/#5、为"双响音""滑块步进 bug"开新 Issue（滑块 bug 已随本分支修复，引用 `cd99120`）、关联 handoff 文档。
3. **test.18（根治包，方案已定）**：
   - `ERROR_DEAD_OBJECT` 原地重建轨道（替代 `check()` 一炸整会话）
   - 锁屏组合拳：电池优化豁免引导页（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 小米自启动图文）、`elapsedRealtime()` 冻结空洞检测、`ACTION_SCREEN_ON/USER_PRESENT` 快速重连、（实验性）allow-idle 心跳闹钟
   - 无损层：PCM CRC32 校验 + NACK 重传（发送端留 ~200ms 环形缓存，RTT≈5ms << 缓冲深度）+ XOR FEC 兜底——**改线上格式必须 bump WFAS 协议版本、更新 `WFAS_PROTOCOL.md`、Android/Desktop 双端协调**
4. 可选打磨：自适应当前 target 暴露到 UI（联动 LinkMetrics）；发送端包节拍整形；AAudio MMAP / API34 `setBitPerfect`。

## 7. 备查事实

- 推送认证：本机 git **必须走 SSH**——`~/.ssh/github_mu23_ed25519` 已注册 GitHub（账号 mu23XR），`~/.ssh/config` 已配置 `Host github.com → IdentityFile`；WorkBuddy 自带 PortableGit 的 HTTPS credential helper（`helper-selector`）在非交互环境会挂起。远端现为 `git@github.com:mu23XR/AudioBridge.git`。`gh` CLI 未安装。
- MuMu 模拟器：用 `D:\game\moniqi\MuMuPlayer\nx_main\MuMuManager.exe`（`sh -v 0 -c "…"` root shell；**不要 adb root**）。实例 0 实测**横屏 1600×900 @240**——技能备忘里的 1080×1920 已过期，**用前必须 `wm size`**；`uiautomator dump` 在动画页静默失败；共享文件夹 宿主 `C:\Users\w1741107948\Documents\MuMu共享文件夹` ↔ 模拟器 `/mnt/shared/MuMuShared`；截图 `screencap -p /mnt/shared/MuMuShared/x.png` 后用 Read 读宿主路径。
- Kotlin 坑：init 块里调成员函数给属性赋值**不满足确定性赋值分析**（编译错 "Property must be initialized"），必须属性初始化器直算或在 init 内直接赋值。
- 工作区会话记忆：`D:/project/app/AudioBridge/.workbuddy/memory/2026-10-02.md` 有全程逐条记录（含诊断推导与踩坑）。
- 分支拓扑：`feat/test17-latency-stability`（HEAD `9294d35`，6 commits）基于 `fix/android-test15-stability`（领先 `main` 191+ 提交，main 完全包含于该线）；远端还有 `chore/project-governance-v1`（Draft PR #2）与独立开发线 `audio-bridge-lab`；远端陈旧分支 `master`/`zh-v1.2` 已完全并入 main，用户明确"先不动"。
