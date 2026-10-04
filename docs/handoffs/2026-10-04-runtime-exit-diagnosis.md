# 2026-10-04 真机退出与通知诊断

范围：连接无线 ADB、保留现场、诊断昨晚/今早退出、比较本地与远端。没有修改产品代码、设备设置、安装包或发布状态。关联既有 Android 稳定性跟踪 #6；本次没有修改 GitHub Issue。

## 已确认的证据

- 两台无线 ADB 已连接：平板 192.168.10.2:46523（25053RP5CC），手机 192.168.10.3:40093（23049RAD8C）。取证时间约 2026-10-04 12:33 CST。
- 两台正式包 `io.github.mu23xr.audiobridge` 都是 test.16，versionCode 609301952。本地 test.17 功能尚未在这些正式包中生效。
- 手机 `ApplicationExitInfo` 最后一条：2026-10-04 03:41:03.246，PID 22441，FORCE STOP，description=`stop io.github.mu23xr.audiobridge due to AutoPowerKill`。
- 同时系统日志依次记录 `PowerChecker.Controller: autoKillApp`、`ProcessPowerCleaner: AutoPowerKill: force-stop`、杀进程、停止 ClientService、取消接收通知 201 和模式通知 701。USER REQUESTED 是系统退出分类，不能解释成用户点了强停。实际发起者是 OEM 电量管理。
- 手机取证时无应用进程、无运行服务、包 `stopped=true`；`RUN_ANY_IN_BACKGROUND=ignore`。历史退出记录还有 10 月 1 日和 2 日的 AutoPowerKill。这次不是“进程仍在但 AudioTrack 不出声”的现场；缓冲增大/播放看门狗不能恢复已经被强停的进程。省电策略为什么在此时触发的具体阈值，现有日志未给出。
- 平板应用 PID 30275、shell 音频桥 PID 26970 仍活着；ShizukuBridgeHostService 是前台服务。02:26:16 旧发送服务停止，02:26:18 通过通知 ACTION_MODE_SEND 启动新发送服务，此后约 10 小时仍在运行。AudioFlinger 显示 remote_submix 在采集；12:33 WFAS_SHIZUKU 仍记录向手机 IP/UDP 端口发送数据。这不证明手机仍接收或播放。
- 平板当前退出记录没有 10 月 4 日应用被杀记录，最近一次是 10 月 3 日 SwipeUpClean。手机 02:26:28 和 02:26:30 有通知 ACTION_MODE_RECEIVE 点击记录；接收服务本轮自 01:52:16 持续到 03:41 被强停。
- 截图中的两条通知分别是发送前台通知 101（wfas_server_v3，service，foreground/promoted ongoing）和模式控制通知 701（audiobridge_control_v1，status，普通 ongoing）。两者渠道 importance=LOW；模式通知被系统放入更多通知，与发送服务是否运行无直接等价关系。本地 test.17 没有修改 NotificationCenter。

## 本地 / GitHub 比较

开始时工作区干净，当前 `feat/test17-latency-stability` @ 46490fd。已成功 fetch origin（使用 GitHub 官方 SSH 443 端口，仅命令级 core.sshCommand，没有修改全局配置）。本地比 `origin/fix/android-test15-stability` @ 54a416f 多 7 个提交；此 feature 分支尚无远端分支。

差异为 14 文件，503 增加 / 778 删除，主要是：延迟设置改为接收缓冲并支持无级滑块/数字输入；自适应 20–80 ms；启动预填与目标相关；单播播放 watchdog/rebuild；已建立会话的重连不重复连接提示音；删除意大利语资源；debug applicationId 增加 `.debug` 后缀；交接文档。ClientService、StreamingActionReceiver、ShizukuBridgeHostService、NotificationCenter 没有此批差异。

其他 AI 交接记录报告 Kotlin 编译、单元测试和模拟器 UI 通过，但真机验收未做。本次为诊断，没有重新运行构建或将这些修改视作已验收。

## 仍未确认 / 下一步

- 用户补充：昨晚两端变关闭发生在 01:00–02:30，随后分别重新点击发送/接收。02:26 的通知命令与这次手动恢复相符；手机 03:41 单端 AutoPowerKill 是之后另一次退出，不能代替前一次现象的解释。
- 用户进一步确认：因为已经断开才主动重新点击发送/接收，两者时间很近。因此原始断开应优先定位在 02:26 手动恢复前几分钟，而不是把恢复命令导致的旧服务停止/重启当作故障起点。该窗口现存系统记录未提供原始断开原因，应用日志已经轮转，仍不能定因。
- 前一次关闭尚不能定因：平板 system 缓冲仅保留到 02:01、events 仅到 02:41、main 仅到 12:25，凌晨应用日志已轮转；系统也没有当天进程退出记录。手机 01:52:14 存在 ClientService/AutoConnectService 停止、01:52:16 再启动，但缺少停止调用原因，不能据此归因用户操作或系统电量管理。
- `handleStoppedWithTask()` 使用 `appTasks.isEmpty()` 推断清任务并选择 OFF，是相关代码审查点；尚无本次日志证明该分支导致两端 OFF，不作为确定根因。
- 今早用户能划掉手机通知的具体通知类型没有当时快照。取证时通知已被系统全部取消，不能反推用户划掉的是前台通知还是普通模式通知。
- 后续修复应分别处理 OEM 后台限制提示/可诊断退出原因、意外服务停止和真实用户 OFF 的区分，以及已有播放链路恢复；不能把 test.17 播放 watchdog 当作系统强停修复。修改/安装需按后续授权执行。
- 下一次复现需保留角色转换原因、服务 start/destroy/taskRemoved、自动重连/桥进程退出记录，并按明确时间与系统电量管理日志对齐。

原始退出记录、服务/包/通知/音频状态和 logcat 已保存在被忽略的 `out/device-debug/20261004/`。全量系统日志含其他应用信息，不提交到 Git。

本轮仅新增此交接并更新 PROJECT_STATE；未提交。ADB 全接口监听被自动审批拒绝后改为仅本机监听，连接成功；没有改变设备网络/电量配置或重启应用。
