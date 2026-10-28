# 接手 prompt：AudioBridge test.17 验收与后续

> 用法：把本文件全文（从下一行的分隔开始）直接发给接手的 agent 即可。仓库里与本文件配套的详细版：`docs/handoffs/2026-10-02-test17-latency-stability.md`。

---

你在接手一个 Android + Desktop 音频串流项目（Wi-Fi 局域网低延迟无损传输）的半成品工作。仓库在 `D:\project\app\AudioBridge\source`（git 仓库本体是 `source/` 这一层，外层只是容器）。

## 你接手时的状态

- 当前分支 `feat/test17-latency-stability`（HEAD `9294d35`，6 笔提交，**只在本地，未推送 GitHub**），基于开发线 `fix/android-test15-stability`。远端 `git@github.com:mu23XR/AudioBridge.git`，默认分支 `main`（落后开发线 191+ 提交，这是正常的）。
- 这 6 笔提交完成了用户报告的 4 个问题的"止血包"：①连接提示音双响（重连静音修复）②延迟设置滑块 21ms 档位 bug + 数值输入 + 接收端标注 ③新增自适应延迟预设（20–80ms 目标带，underrun 驱动）④接收端播放看门狗（underrun 风暴/播放头停滞 → 原地重建 AudioTrack，5s 未消费 → 会话重建）。诊断根因、每笔提交明细、新 API 列表都在 `docs/handoffs/2026-10-02-test17-latency-stability.md` 第 2、3 节。
- 已验证：`:app:compileDebugKotlin` ✅、`:app:testDebugUnitTest` ✅、MuMu 模拟器设置界面 UI 验收全绿。
- 未做：**真机验收**（平板发送 → 手机接收）。这是你接手后的第一件事。

## 你的任务（按顺序）

1. **真机验收**。先构建本地验收包：`cd D:/project/app/AudioBridge/source/android && ANDROID_HOME="D:/apps/Dev/android-sdk" GRADLE_USER_HOME="D:/apps/Dev/gradle-home" ./gradlew :app:assembleDebug`——debug 包名带 `.debug` 后缀，与手机上正式签名的 test.16 **共存安装**，不用卸载。验收前把手机上该应用的省电策略设为"无限制"+允许自启动（HyperOS 冻结的应用内引导页还没做）。验收场景与通过标准在详细文档第 6 节：①开关发送端 Wi-Fi 5 秒看重连是否无声（双响音）②播放中锁屏 30–60 分钟（锁屏断连）③锁屏空闲后发送端开播不解锁看是否恢复 ④手机远离路由器看 logcat `[PLAYOUT][ADAPTIVE]` 爬升回落（自适应）⑤长挂机无声是否自动恢复（看门狗）。logcat 采集：`adb logcat -c && adb logcat -v time | grep -E "\[CLIENT\]\[DISCONNECT\]|SESSION-END|\[PLAYOUT\]|PowerKeeper"`。把每次测试的结论和关键日志原句记进 `docs/PROJECT_STATE.md`。
2. 验收全绿后：**本地合并 main → 打 tag（v1.3.1-test.17）→ push main + tag**（用户工作流：测试全过才推）。推送走 SSH（已配好 `~/.ssh/github_mu23_ed25519`，**不要用 HTTPS，本机 HTTPS credential helper 会挂起**）。推完后按 `AGENTS.md` 纪律补 GitHub 记录：更新 Issue #4/#5，为"双响音"和"滑块步进 bug"各开新 Issue（引用对应提交），真机验收通过才允许关单。
3. 验收发现问题 → 修在同一个分支上，重新验收。
4. 之后是 test.18 根治包（方案已与用户定稿）：`ERROR_DEAD_OBJECT` 原地重建轨道、锁屏冻结组合拳（电池豁免引导页 + elapsedRealtime 冻结检测 + 解锁快速重连）、无损层（PCM CRC32 + NACK 重传 + XOR FEC，**改线上格式必须 bump WFAS 协议版本并同步 `WFAS_PROTOCOL.md` 与 Desktop 端**）。

## 环境雷区（本机实测，别踩）

- **代理会污染/拦截国外源**：Maven Central 全 403、Gradle 官方下载被注入假内容。已建 `D:/apps/Dev/gradle-home/init.d/mirrors.init.gradle`（阿里云镜像前置），构建必须带 `GRADLE_USER_HOME="D:/apps/Dev/gradle-home"`。gradle-home `.tmp` 偶发"拒绝访问"就重跑。
- 构建：无 local.properties、无 flavor、JAVA 21；命令模板在上面任务 1 里。不要把 local.properties 写进仓库。
- 仓库纪律（`AGENTS.md`）：默认分支 main；真机验收通过才关 Issue；文档随 PR 同步更新；**不要合并挂红的 CI**。
- 模拟器测试可用 MuMu（实例 0，**横屏 1600×900**，先 `wm size` 核对；`D:\game\moniqi\MuMuPlayer\nx_main\MuMuManager.exe sh -v 0 -c "…"`），但它只能验 UI，断连三类必须真机。
- 项目里 `docs/PROJECT_STATE.md` 是状态权威、`WFAS_PROTOCOL.md` 是协议唯一权威、GitHub Issues 是任务清单权威；`docs/handoffs/` 是交接记录存放处。
- 用户偏好：中文交流；代码提交用 conventional commits（仓库既有风格是英文小写）；**动 GitHub（push/开 PR/关 Issue）之前先经过用户确认或本地验收**；不要替用户假设没说过的事实。

第一句话先向用户确认：真机现在方便连吗？方便的话直接从任务 1 的验收开始。
