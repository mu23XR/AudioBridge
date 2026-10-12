# Android运行诊断与夜间故障修复

2026-10-12，用户授权实现。分支fix/android-runtime-diagnostics，基线test17 @2b69037，CLI worktree位于runtime-fix（托管工具因任务根非Git目录失败，故使用标准Git worktree；不触碰既有桌面工作区）。

工作项：#32 持久脱敏日志与导出；#33 自动连接服务超时/配额崩溃；相关#4/#5/#14 播放恢复，#6验收门禁。

已证实：手机AutoConnectService dataSync超时/重启配额导致崩溃；手机另有OEM AutoPowerKill；平板03:09/03:34发送服务停但主进程仍活，原始桥错误日志已轮转。不能把全部故障归于手机或声明日志功能已修复夜间音质。

实现阶段：先补持久日志/有界IPC遥测及服务超时处理；针对静音供给误判进行回归验证；再构建签名独立测试包并收集真机声音/断连现场。允许安装测试通道、保留正式test16；不发布stable、不修改OEM电量/代理/音量设置。

验收：日志轮转/脱敏/重启/导出；dataSync超时无应用崩溃；Android compile/tests/lint；双端采集与播放统计可导出；静音及正常播放、角色切换/停止；长时间问题保留open至实测通过。

当前状态：实现已完成第一轮，43单元测试/编译/lint与签名preview/AndroidTest构建通过。真机安装、导出重启、播放尚未做；追加边界修订后最终复核继续。源码Android-only，无协议线格式变化，test18仅独立预览，不发布stable。
