# 2026-10-04 本地 test.17 代码评审

任务：评审其他 AI 的本地修改是否合理、规模与风险，并建议合并通知应基于哪个版本。只评审，不改产品代码、不安装或发布。基准 `origin/fix/android-test15-stability` @ 54a416f；被评审 HEAD 46490fd，分支 `feat/test17-latency-stability`。开始时已有本轮设备诊断文档修改，予以保留。与既有稳定性 tracker #6 相关；实现前应更新/建立对应 GitHub 工作项。

## 结论与范围

建议保留本地分支，修正恢复/自适应缺陷后，在此基础上实现通知合并；不建议全盘丢弃。延迟标注、无级滑块、数值输入、固定预设，以及针对已建立会话的重连提示音控制方向合理。debug 包名后缀方便并存测试，但不能代替正式签名覆盖升级验收；两份应用须避免同时占用捕获/网络端口。

7 个提交、14 文件，+503/-778。删除中 746 行是意大利语资源；除该资源和 3 个文档外，Android 代码/构建/剩余资源为 +353/-30。功能改动集中于设置 UI、SettingsDataStore、NetworkManager、PlayoutGovernor、ClientSessionController；协议和发送端服务没有整体重写。不是大规模重构，但实时音频逻辑风险集中，不能按新增行数判断安全。

## 发布前需处理的发现

1. **[P1] 重建失败没有进入明确恢复路径。** `NetworkManager.kt:2550–2578` 先释放旧 AudioTrack，再尝试建立新 track；异常时返回 false。`2835` 调用方忽略返回值且先清除 rebuildRequested，继续使用指向已释放对象的 audioTrack/playout。若新 track 建立并注册后，预填/play 抛错，新 track 没有在该 catch 中释放或撤销注册。应让失败立即进入正常断连重连，并确保所有部分建立的资源被释放；成功后再原子交换会话持有的 track。
2. **[P1] 500 ms 局部恢复可能遮蔽新的 5 s 重建条件。** `PlayoutGovernor.writePcm():108–118` 在播放头停止 500 ms 后 flush/reset/play，并重置 lastProgressAt；新 watchdog 使用 `stalledForMs() >= 5000`。持续收到 PCM 而 flush/play 一直不能恢复的场景下，500 ms 计时被不断重置，5 s 条件无法累计。固定模式还有 underrun storm 的重建条件，自适应模式没有对应兜底：到 80 ms 上限后仍持续欠载，只会尝试 retarget。应保留跨局部恢复的失败计数/真实播放进度超时，到阈值重建或重连。该发现是代码推导，尚未真机复现，不等同于 03:41 OEM 强停原因。
3. **[P2] 升级默认值会覆盖旧用户的实际延迟选择。** `SettingsDataStore.kt:408` 对缺少新 adaptive_latency 键的用户无条件返回 true；旧安装即使保存 41/60/100 ms，也会进入 `NetworkManager.kt:2322` 的自适应 20 ms 起点，不再按其原值播放。与文档“新安装默认自适应”不符。应区分新安装/已有 LATENCY_MS 的迁移；已有手动设置需保留，或明确由用户开启。
4. **[P2] 自适应的“稳定 30 秒后下降”并未检测稳定。** `NetworkManager.kt:2619` 从最后目标变化起满 30 秒便尝试下降；单次欠载、未达到连续两秒阈值的持续欠载、无 PCM 空闲，都不会重置稳定计时。到 80 ms 仍发生欠载时，无法再上调也不更新最后变化时间，交替采样可能又下降。应独立记录最近异常和有效音频稳定区间，避免把空闲或有欠载的时间当成稳定期。
5. **[P2] 多播自适应选项实际未自适应。** 多播分支 `NetworkManager.kt:2938–2969` 按 mcAdaptive 创建 20 ms governor/较大 track，但后续没有对 mcPlayout 调用 retargetMs，也没有单播新增的健康观察/rebuild。用户选择自适应后，多播会长期固定在 20 ms。需补齐或明确限制该选项适用传输。
6. **[P2] 看门狗与接收循环共享可变状态缺少同步。** 父任务在 Dispatchers.IO；watchdog 是并行子协程。新共享 rebuildRequested/时间、playout引用、retarget 修改的多个阈值都没有统一串行所有权或同步。`stalledForMs()` 经 bufferedFrames()/playedFrames() 还会写 head 计数，与 write/reset 并发。可能看到不一致状态，并在重建时读取已经释放的 track。应由接收循环统一修改播放状态，看门狗只发送请求或读取不可变快照；不要仅给布尔量加 volatile 就视作解决所有竞争。

## 通知合并建议

本分支没有修改 NotificationCenter、ClientService、StreamingActionReceiver、ShizukuBridgeHostService，因此通知合并与这些新功能没有必要的冲突。基于本地分支做一项独立、范围清楚的通知修改即可。运行时由实际前台服务持有一条含模式切换的通知，不再并发发布普通模式通知；关闭/空闲时再显示一条普通控制通知。需要验收发送→接收→关闭、自动重连、自动发现服务并存、通知操作、清任务等路径，避免取消正在用作 startForeground 的通知。具体按钮布局尚未实现或定案。

系统 AutoPowerKill、第一次双端关闭仍分别按设备诊断工作项跟进；通知合并与播放 watchdog 都不能单独解决 OEM 强停。

## 验证与交接

- 静态检查完整提交差异及调用链；本批提交没有新增测试，已有 AudioSilenceRegressionTest 主要覆盖旧的非阻塞写/500 ms 恢复，未覆盖新 watchdog、自适应与失败清理。
- 本轮尝试 `compileDebugKotlin` + `testDebugUnitTest`：默认 Gradle home 缺发行版，下载被网络权限阻止；改用已有 `D:/apps/Dev/gradle-home` 和 `D:/apps/Dev/android-sdk` 离线运行后，Gradle 启动报 `Unable to establish loopback connection`，任务没有执行。因此本轮不能报告编译/测试通过。其他 AI 文档记录曾通过，不能代替本轮结果或新增行为验收。
- 仅保存本评审及 PROJECT_STATE 的评审摘要，没有改产品代码、提交、push、安装或发布。后续优先修复发现 1/2/3，再完善自适应和测试；自适应未验收前建议不要自动替换现有固定值。
