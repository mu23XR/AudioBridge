# test.17 修正与独立测试通道

## 恢复实施（#17，2026-10-04）

阶段更新：b3d64d0 保存播放/延迟/通知修复，df460af 保存独立签名预览通道/更新选择/lint 处理。正式 release.yml run37181009171 的 Android、Windows、governance 和发布任务均成功，v1.3.1 正式版已发布。历史 test.13 两份 APK SHA-256 同为 F98457EE6D27D58652A7C8C3D9AD90DD3F1F57ED828CCFB026239B2F1F3DA18C；用户授权归档并校验后删除，仍待浏览器登录才能上传。不在 Git 中存历史 APK。Signed preview assemblePreview/lintVitalPreview 与 39 单元测试通过，APK/真机验证继续。

用户已认可同 test.16 永久签名 + 不同包名的双通道并授权实施。保留两设备原 test.16；手机无线端口刷新到 41947，已连接。GitHub 没有 test.13 Release，用户另授权补发历史归档、重新下载核验后删除两份旧 APK；上传目前等待用户在内置浏览器登录，不删除本地副本。

正式发布分支 release/test16-stable 从原 test.16 @60137ef 隔离，提交 18ea088/tag v1.3.1 已推送，run37181009171 在构建。仅修发布流/文档，产品源码无改动。开发分支同步发布工作流修正，避免继续保留截断的 Windows 脚本。

新增 ReleaseChannel 选择/排序和三个回归测试：正式通道只选语义 stable，独立测试只选 Preview 命名 APK，排除旧正式包名 test.16 等历史测试资产；自动/手动更新入口指向选定版本。CI 新资产命名 AudioBridge-Android-Preview。Shizuku tag 带 context.packageName、provider authority 已有 applicationId、通知 PendingIntent 显式组件均按包隔离。Preview launcher 加 T 标记。

移除 startClient 不准确的无条件 RECORD_AUDIO 声明（接收无需麦克风；返送麦克风仍由既有权限路径控制），通知发布处理权限撤销竞态，shell uid 录音调用使用局部、说明原因的 lint 注解（拒绝仍报告并抛出），widget preview tint 按 AppCompat 修正。本轮无需修改 Composables 缩进：重新执行后旧报告位置不再存在问题。39 单元测试通过；lintDebug 0 errors、230 warnings、20 hints，通过。开发/测试 CI lint 改为真正门禁，不加 baseline。不把这些结果当作长时间真机验收。

正在用用户提供且已核验的永久密钥构建 signed preview；凭证不打印，临时 keystore 在 finally 中清理。尚未宣称 APK 验签、安装或长期播放已通过。待编译完成后明确提交、推送并建立 Draft PR，继续 CI 和真机验收。

日期：2026-10-04。分支 `fix/test17-adaptive-notifications`，保留原分支 `feat/test17-latency-stability`。基于本地 46490fd，不撤销其他 AI 已有提交。工作项：#14 播放恢复、#15 延迟模式、#16 通知合并；稳定性与正式版门禁仍为 #6。用户要求修复并规范分支/Issues/PR，同时保留设备上可用的 test.16。

## 已实现，待完整真机验收

- 设置只有“自适应”和“手动”两种模式。手动显示无级 20–400 ms 滑块和数值输入，不再提供 20/40/60 挡位。切换到自适应不删除手动值；改包大小不会意外改变延迟模式。新安装默认自适应，升级用户未存新模式键时按已有手动值保留手动模式。连接时读取设置，界面明确重新连接生效。
- 新 PlaybackHealth 使用单调时钟与连续样本：有 PCM 且连续欠载才上调，持续健康音频满 30 秒才下降；空闲、欠载、样本间隔中断重置稳定计时。到上限后欠载风暴仍可升级恢复。
- 单播看门狗读取不可变原子快照，不访问 AudioTrack/可变 governor；目标调整和播放器重建由接收循环执行。重建失败清理新建资源并进入正常断连重连，成功后保留自适应已达到的目标。
- Governor 对重复无效 flush/play 保留失败次数，实际播放头前进才清除；三次失败可触发重建，避免 500 ms 复位不断遮蔽 5 秒看门狗。多播使用同一健康策略，有异常经原路径重连恢复。未声称能恢复已经被 OEM 强停的进程。
- WFAS 运行通知包含发送/接收/关闭操作，普通模式通知在运行通知存在时不重复发布；音量通过点击运行通知的已有控制面板调整。其他接收类型保留模式操作。关闭/空闲的普通模式通知可划掉。没有将多个独立前台服务粗暴共用一个 ID，自动发现与独立协议服务仍保留各自前台生命周期。
- local debug 为 `.debug`，CI signed preview 为 `.test`，正式 release 原包名不变。测试包名称为 AudioBridge Test / AudioBridge 测试。CI 测试工作流改构建 assemblePreview；仍验证永久签名，未换正式签名、未生成替代正式密钥。
- Android/Desktop/governance PR 校验覆盖任意目标分支，支持以已有修复分支为基线的堆叠 Draft PR。签名测试的 build-request 仍 publish=false；不发布正式版、不自动合并。

## 实际签名与设备

本轮从当前手机只读拉取 test.16，确认正式包 `io.github.mu23xr.audiobridge`，versionCode 609301952 / versionName 1.3.1-test.16，证书 SHA-256 `4DDF267D25A73F0A28446C9E77293204E32F6777C63417FEB7BD925040FEFB63`。与 SIGNING.md 的永久证书一致；历史 #153 的证书为 `4CC4F504BA953B31FFABB2555E3099DA31D94259CF58259B9E6B6FAE19782792`，不同。旧 phone-installed.apk 备份实际是 test.13，未误用作本次 test.16 证据。

Debug/preview/正式包的权限、配置与 Shizuku 授权独立。安装独立测试包不删除/覆盖 test.16；测试发送时仍须避免正式与测试应用同时争用采集、默认端口。长时间验收前应由用户主动停正式会话，再启动测试会话。

## 验证阶段

- 第一轮 Kotlin 编译、单元测试与 assembleDebug 成功。
- 新 PlaybackHealthTest 五个用例覆盖自适应/手动、欠载、健康间隔、静音、重复恢复、样本中断和旧设置默认；AudioSilenceRegressionTest 增加无效恢复累计/真实进度清除用例，当前六个用例通过。
- 第二轮编译、测试、debug 包构建与 preview 清单处理成功，preview 清单应用 ID 为 `.test`；全体工作流 YAML 解析成功。
- lintDebug 执行完成但报 11 errors、229 warnings、20 hints，未通过。主要位于原有权限调用、Composables 缩进、widget android:tint。没有新增 baseline 或关闭 lint 来伪装成功；需在 #7/#6 跟进，Draft PR 不得直接当作稳定发布验收。
- 最终 debug/preview Kotlin 编译与 assembleDebug 成功；36 项单元测试全部通过。全体工作流 YAML 解析与 git diff --check 通过。
- 用户要求先看方案，暂停后续修改/提交/推送/PR/发布。产品改动目前均未提交，独立本地分支及 #14/#15/#16 已建立；没有 remote PR。
- 暂停前已发出独立 debug 包并存安装命令。平板返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`；脚本失败即停止，手机安装未执行。没有成功安装新测试包，没有删除或覆盖 test.16。后续应先确认方案，再由用户允许设备安装流程，不应自动重复安装。

Windows 本机 JDK 21 的 Unix-domain 临时管道 connect 失败，最初 Gradle 无法建立回环。构建命令仅通过进程级 JAVA_TOOL_OPTIONS 设置不存在的 jdk.net.unixdomain.tmpdir（令监听器回退 TCP）和现有项目 out/java-tmp；没有更改系统/全局 Java 配置。Gradle home 使用已有 D:/apps/Dev/gradle-home，SDK 使用 D:/apps/Dev/android-sdk。新增依赖已按正常 Gradle 构建下载，未引入产品依赖。

本轮还保留并更新设备诊断与评审文件，不将原始设备日志/APK提交到 Git。第一次双端关闭原因未确定，03:41 手机 AutoPowerKill 未解决；不能因新包编译通过关闭这些问题或直接把 test.16 推为 Stable。
