# 真机调试预检（2026-10-01，Asia/Shanghai）

## 范围与状态

用户请求查看 GitHub AudioBridge 项目，并准备连接真机定位尚不明确的 bug。本次仅完成只读预检，未修改源码、设备配置、应用安装或 GitHub 状态。尚未复现 bug，不能判定修复有效。

当前本地目录为空，无 Git checkout，无法检查本地分支或未提交修改。此文件为本地交接记录，尚未提交 Git。

## 已核对的证据

- 仓库：https://github.com/mu23XR/AudioBridge，默认分支 main；观察到 main 最新提交 3b1915c。
- 活跃 Draft PR #2：chore/project-governance-v1，观察到 head 2d72885。
- 已读开发分支 AGENTS.md、docs/PROJECT_STATE.md、docs/TESTING.md，以及全部 7 个 open Issues 和 PR #2 描述。
- #6 为稳定性总工作项；主要待真机验收问题为 #3 清后台后 Shizuku/音频路由残留、#4 长时间静音断连、#5 延迟增大。#9 通知按钮重复执行亦待验收。
- main 与开发分支进度不同，手机测试包不能直接假定对应 main。Issues 指向 test.13；当前查询到最新发布的 Release 为 v1.3.1-test.11。
- ADB 可用，路径 D:\tools\platform-tools\adb.exe。
- ADB 出现两个同型号 23049RAD8C / marble 的无线连接条目（transport 2、3）和一个模拟器。两条无线连接是否同一物理设备尚未独立核实。
- 本次只读查询使用 adb -t 3；设备 Android 15 / API 35。
- 已装 io.github.mu23xr.audiobridge：versionName 1.3.1-test.13，versionCode 2300013；另装历史 com.cuscus.wifiaudiostreaming.lab 包。
- 查询时 Shizuku server 与 AudioBridge 应用进程存活，ClientService 是前台服务。此信息不能证明接收连接正常。
- 有限读取最近 logcat 未取得能定位上述 bug 的应用异常证据。

## 下一步

1. 确认用户本次发送端/接收端组合、具体异常操作和实际现象；已在聊天提出。
2. 每次采集前重新运行 adb devices -l 并确认设备，transport id 可能变化；避免默认选中模拟器。
3. 核对打开的是永久包还是旧 lab 包；未获授权不卸载或清数据。
4. 针对选定 Issue 采集操作前后 logcat、服务/进程与音频路由状态。先建立正常播放基线，再执行单一复现步骤。
5. 若进入代码修复阶段，先取得对应源码 checkout，确认测试包来源与提交，认领已有 Issue 并按其验收标准验证。当前无需创建重复 bug Issue。

当前无根因结论、无修复、无测试通过结论，未更新全局项目状态或关闭 Issues。

## 用户确认后的任务范围

- 用户已授权修复 #3 和 #4，测试组合为平板发送到手机。
- 发送端平板尚未连接；用户正在询问无线 ADB 配对方法。已核对 Android 官方 ADB 文档，电脑 ADB 为 37.0.1。
- 正在尝试将开发分支 clone 到 source 子目录。默认 Schannel 报 SEC_E_NO_CREDENTIALS；仅对单次 clone 使用 http.sslBackend=openssl 后进程仍在运行，尚未确认成功。未修改全局 Git 配置。
- 下一步先完成平板配对与连接、确认型号和安装版本，然后对 #3/#4 采集基线和复现日志。获得源码后先检查 Git 状态并按既有 Issues 认领工作，再实施修复。
