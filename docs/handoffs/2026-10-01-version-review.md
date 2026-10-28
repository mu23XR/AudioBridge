# 最新代码与真机 test.13 差异审查

日期：2026-10-01（Asia/Shanghai）。范围：只读源码、GitHub CI、设备安装包审查与修复建议；未修改产品实现、配置、安装版本或 GitHub 状态。

## 版本基线与证据边界

- GitHub 最新开发分支 chore/project-governance-v1：2caa437b399c1759f9202d4095829b5930693cea，PR #2 尚未合入 main。
- main：3b1915c4c37d761b56727a9d1364c2bfec2aa2be；不能拿 main 代表用户所说的清理后最新版。
- 本地 checkout 仍为 2d72885；fetch 后与最新提交仅 desktop/Main.kt 的 13 行捐赠计时器删除存在差异。本次最新 Main.kt 内容通过 git show origin/chore/project-governance-v1 核对，未覆盖本地工作区。
- 两端安装包均为永久包 test.13 / 2300013。只读 adb pull 后，两端 APK SHA256 相同：F98457EE6D27D58652A7C8C3D9AD90DD3F1F57ED828CCFB026239B2F1F3DA18C。
- 查到 17 次成功 test.13 构建，所有构建提交的 android/app 与首次构建配置提交 71765f9 相同。最新成功 test.13 run 36725036328，对应源码 34eb7524f72496f33687627dc5c5583aad973cfa。
- 以 34eb752 为 test.13 源码基线对比最新：67 文件，659 行新增、1798 行删除。源码家族已定位；仍未完成设备 APK 与 CI 产物的逐字节匹配（工具返回的临时下载地址在本机返回 HTTP 403）。不能声称已唯一确认设备来自哪个构建 run。

## 主要差异

1. Android/Desktop 去掉原作者产品介绍、捐赠入口/弹窗/偏好与部分计时器；下载、源码和浏览器播放器入口改指 AudioBridge。
2. 普通连接超时改为状态文字，保留协议不兼容提示。
3. QR/custom URI 从 wifiaudio:// 与上游 HTTPS 配对链接迁移为 audiobridge://；Manifest、Desktop 协议注册和解析器同步改变。
4. 通知 SEND/RECEIVE 从角色选择变为每次执行重启；正常应用内角色选择仍保持原语义。
5. CI、贡献规范、签名/发布和工作项文档完善，Android lint 当前使用 continue-on-error。
6. Shizuku 抓取/音量实现和 PlayoutGovernor 相比 test.13 没有变化；NetworkManager 的本轮变化是品牌、捐赠、超时 UI 清理，不包含接收端阻塞写入修复。

因此启动发送继承静音、HyperOS 暂停零音频后接收卡住的现场结论仍适用于最新源码；清后台恢复的本轮成功结果仍需在新包复测。

## 已确认问题

### P1：正式发布 workflow 无法解析（历史遗留，最新版仍存在）

.github/workflows/release.yml:226 出现顶格的 `) {` 和残留 Windows/发布片段；后段还有重复 release job。当前 GitHub 对每次提交产生失败的 release workflow 记录，latest run 36754929410。本机 PyYAML 解析同样失败，定位 226–227 行；其他四个 workflow 能解析。

建议：删除第一次完整 release job 后的重复残片，保持唯一 android/windows/release 三个 job；补 YAML 语法与重复键校验，随后核对 GitHub Actions schema。不要用跳过发布检查处理。

### P1：Desktop 检查无法编译（旧测试缺陷，新 gate 暴露）

latest run 36754934465 / job 110022650020 在 compileTestKotlin 失败。RtpSdpCheck.kt:12/20 的私有 check/eq 与同一默认包 DlnaProtocolCheck.kt:5/10 的公开同签名函数冲突。两份定义在 test.13 基线已存在；品牌更新只改测试数据与期望，不是造成重名的改动。

建议：将 RTP 辅助函数放入独立私有对象，或使用清晰的 RTP 专用名称；不删测试、不跳过 check。随后把以 main() 运行的协议自检显式接入 check，否则普通 Gradle test 不会自动运行这些自检函数。

上一轮 run 36742292755 是 Main.kt 调用已删除的 setDonationQualified 导致 compileKotlin 失败；2caa437 已删除该残留计时器，不应再列为最新源码未修复问题。

### P2：测试版升级同版本正式版会被错误判断为 Ahead（历史遗留）

Android UpdateChecker.kt:65 与 Desktop 同名文件按数字片段比较，缺少 prerelease / build metadata 语义。把现有 Android compareVersions 函数原样提取到临时 Kotlin 程序执行四组对照：

- 1.3.1 对 1.3.1-test.13：应正式版更新，实际判断更旧。
- 1.3.1-test.13 对 1.3.1：方向同样相反。
- 1.3.1+build.2 对 1.3.1+build.1：应等价，实际判更新。
- 1.3.2 对 1.3.1-test.13：正确。

结果 1 通过、3 失败。建议两端统一明确的版本规则，支持 stable > 同核心 prerelease、忽略 build metadata；增加真实版本用例，不引入新依赖。

### P2：HTTP 停止先清空监听 socket 引用（历史遗留）

NetworkManager.kt:3283 先令 httpServerSocket=null，3285 再 close，实际无法关闭旧监听 socket。launchHttpSidecar 在 3401 阻塞于 ServerSocket.accept()，Job.cancel 不会让该调用可靠返回，因此 finally 的本地 close 也可能无法执行，留下端口被占用/重启失败。未开启设备 HTTP 模式复现。

建议：取出旧 socket 并主动 close，随后清理引用；确认客户端 socket 同样在停止时能解除阻塞。做 start → 无客户端 → stop → 同端口 restart 回归。

### 文档约束回归：22 个原版权头被整体替换

diff 中 22 个 `Copyright (c) 2026 Marco Morosi` 被替换成 `Copyright AudioBridge`，例如 NetworkManager.kt:2、ProtocolRegistrar.kt:2、audio_engine.c:2。Issue #8 明确要求 Preserve required source copyright/license headers。建议恢复原有源码版权头，项目自己的新增版权可另外添加；运行 UI 的作者介绍和捐赠入口保持删除。这是仓库自身约定检查，不等于对全部许可证事项完成法律审查。

## 最新通知改动的静态风险（未在最新包上实测）

### P1：旧接收任务清理可覆盖新接收任务

NetworkManager.startClient 在 2237 调用 openStreamGeneration，却没有像 sender 一样捕获/检查 generation。旧任务 finally 在约 3190 异步调用 finishClientTransportAttempt，后者无条件清空 streamingJob、activePeerIp 和 isStreamingCurrent。restartCurrent 新任务已开始后，旧任务仍可能执行该清理；旧任务自己的 ClientSessionController token 校验位于回调中，无法阻止此前 NetworkManager 的全局清理。通知 RECEIVE 每次强制重启使触发机会增加；现场曾见多条旧 session 延迟退出，但未据此宣称最新通知逻辑已真机复现。

建议：每个接收尝试持有 generation 和自己的 track/socket/job；只有当前尝试能修改全局状态，旧任务仅释放自己的资源。先主动解除阻塞，再结束旧尝试；禁止旧 finally 影响新任务。

### P2：异步发送启动缺少完整的角色/代次校验

RuntimeModeController.kt:193 的角色校验在 SettingsDataStore.first() 之前；startConfiguredSender:200 等待偏好后不再核对角色。等待中用户按 OFF/RECEIVE，旧任务仍可能启动发送。两个快速 SEND 命令也会创建多个未持有的 launch；固定 250ms 延时不能证明旧桥已退出。

建议：模式命令保存 Job 和 generation，OFF/角色切换取消待启动任务；每次挂起点之后核对代次，在真正开始桥/服务前再核对一次。退出与启动串行协调，不能依赖固定延迟作为唯一条件。

## 确认的兼容变化

最新版解析器只接受 audiobridge://。test.13 生成/识别旧 wifiaudio:// 或上游 HTTPS 链接，因此旧新两端扫码不能直接兼容；WFAS UDP v2 数据协议未随品牌清理改变，不能把扫码失败说成音频协议不兼容。

建议两端一起升级并重建二维码；如需渐进迁移，可临时接受旧 custom scheme 的输入，同时只生成新 scheme，不恢复旧网站依赖。升级说明需明确这一点。

## 验证结果与限制

- 最新 2caa437 Android run 36754934439：testDebugUnitTest、lintDebug、assembleDebug 均成功；测试断言数量未从 CI 报告取得，不编造数量。
- 最新 Desktop run 36754934465：compileTestKotlin 失败，协议自检未通过；未生成新的通过结论。
- governance run 36754934549：成功，但没有发现上述 workflow YAML 错误或版权头回归。
- 本地五个 workflow YAML 解析：4 通过、1 失败。
- 提取原版本比较函数的 JVM 对照：1 通过、3 失败；临时文件 out/review/VersionComparisonProbe.kt / version-probe.jar，未加入产品测试或修改源码。
- 本机未配置 Android SDK，本次不安装 SDK/依赖、不在设备安装新 APK；完整 Android 编译使用已有 GitHub CI 证据。
- 本次未宣称发现所有潜在 bug；网络/生命周期静态风险需在固定提交的同签名 APK 上验证。

## 推荐实施顺序

1. 固定在最新清理后的 Android 源码上做修复，保留已授权的品牌清理；不回退到 main 或旧 test.13。
2. 先修接收端可取消播放与 generation 所有权、发送启动后的系统静音同步，以及通知模式命令竞态；保留现有 owner-death/task-removal 清理路径。
3. 修 Desktop 测试冲突、正式发布 YAML、版本比较与源码头回归；HTTP 问题单独范围清晰的修复。
4. 使用永久签名、单调更高 versionCode 的明确测试版本。构建记录附源码 SHA 与 APK SHA256，防止多个不同产物复用同一 test.N 名称导致无法溯源。
5. 两端一起升级验收：speaker=0 启动发送无需音量键；静音 3 分钟恢复；Home/锁屏继续传；5 轮清后台释放；连续 RECEIVE 不留旧轨道；SEND→OFF / SEND→RECEIVE 快速切换；新 QR 两向扫码；正式版升级提示；HTTP 同端口重启。

## 本轮改动与交接

### 用户确认的后续范围

用户提供并要求核对历史对话“删原作者窗口”（6abb488b-a6a4-83e8-a4c6-04fdff7aaade）。已读取：其明确要求删除原作者产品 UI、捐赠和网站依赖，README 简短说明历史来源；该对话中的执行报告同时称保留继承代码的 copyright/EUPL/第三方声明，并区分新增文件自身版权。由此纠正本报告“22 个头整体恢复”的过宽建议：这 22 处替换只是待逐文件核对的线索，不能单凭旧头断言每份代码归原作者。实施前结合文件来源及提交历史判断：自行独立新增的代码标注用户版权；仍继承/修改上游代码的部分保留适用的原声明并补充用户修改声明。采用 Shizuku 替代捕获方式本身不证明网络、播放等其他继承实现已全部独立重写。当前用户强调产品由其独立维护，不恢复已删除的作者 UI 或无功能影响的旧内容；本轮仅核对和记录，未修改源码。

用户明确要求：最新版已删除且不影响正常功能的部分不恢复；后续修复围绕正常功能问题进行。作者介绍、捐赠入口和无功能影响的品牌内容继续保持删除。源码版权头属于许可证声明，不是运行功能；LICENSE.md 第 67 行及 EUPL 官方说明要求保留既有版权声明，此要求已向用户说明，但本轮没有恢复源码头或修改产品代码。正式实施时应单独说明这一事项，不把恢复 UI 或旧功能混入修复。

许可证依据：https://interoperable-europe.ec.europa.eu/collection/eupl/how-use-eupl 。

仅新增本审查记录并更新诊断交接入口；未改变产品实现，没有可提交的功能修复成果。原 #3/#4/#6/#7/#8/#9 保持 open；新发现尚未写入 GitHub Issues，实施前按仓库规则认领/补充对应工作项。PROJECT_STATE 全局事实未改。
