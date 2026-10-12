# Android运行诊断与夜间故障修复

2026-10-12，用户授权实现。分支fix/android-runtime-diagnostics，基线test17 @2b69037，CLI worktree位于runtime-fix（托管工具因任务根非Git目录失败，故使用标准Git worktree；不触碰既有桌面工作区）。

工作项：#32 持久脱敏日志与导出；#33 自动连接服务超时/配额崩溃；相关#4/#5/#14 播放恢复，#6验收门禁。

已证实：手机AutoConnectService dataSync超时/重启配额导致崩溃；手机另有OEM AutoPowerKill；平板03:09/03:34发送服务停但主进程仍活，原始桥错误日志已轮转。不能把全部故障归于手机或声明日志功能已修复夜间音质。

实现阶段：先补持久日志/有界IPC遥测及服务超时处理；针对静音供给误判进行回归验证；再构建签名独立测试包并收集真机声音/断连现场。允许安装测试通道、保留正式test16；不发布stable、不修改OEM电量/代理/音量设置。

验收：日志轮转/脱敏/重启/导出；dataSync超时无应用崩溃；Android compile/tests/lint；双端采集与播放统计可导出；静音及正常播放、角色切换/停止；长时间问题保留open至实测通过。

当前状态：最终本地43单元测试全部通过，lintDebug零错误（217 warnings、20 hints），签名preview/AndroidTest及lintVital构建成功。test18/code610120941已升级到两台设备，正式test16/code609301952均保留。Android-only，无协议线格式变化，test18仅独立预览，不发布stable。第一轮提交6fc4c2f的Android、Windows、governance、测试APK CI均成功；追加启动失败证据改动需在推送后复核CI。

真机阶段：安装诊断AndroidTest助手被系统取消，两台最终均锁屏，已请求用户解锁；没有绕过安装限制。平板17:39发送启动失败，三种AudioPolicy sink均null，REMOTE_SUBMIX音量同步等待超时；当前音乐路由bt_a2dp(0x80)，无残留采集UserService。路由检查代码与test17一致，不能据此归因于新增日志或昨夜断连。已补启动失败前同步持久保存IPC事件/策略错误及实际设备掩码，并通过最终构建。

待执行：设备解锁后安装AndroidTest助手并各运行两次，确认导出、脱敏、跨进程留存；实际设置→信息的SAF导出；保持原音量/代理/延迟，平板发送手机接收，记录正常PCM和至少180秒静音后恢复；复测发送启动路由问题并抓完整事件链。再做后台/角色切换/跨夜验收及受控Android15+超时验证。尚未开始持续音频监控，#32/#33及播放/OEM问题保持open。
