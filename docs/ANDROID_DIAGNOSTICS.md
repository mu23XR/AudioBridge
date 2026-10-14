# Android诊断日志

设置→信息→导出诊断日志，选择保存位置，生成ZIP。用于核对双端版本、会话开始/结束、发送采集错误、重连、播放恢复及音频健康统计。

日志存于应用私有files/diagnostics；8个文件，每个不超过1MiB，总量不超过8MiB。初始化时过期7天的文件删除，容量满时提前轮转，不能承诺完整保存七天。写入队列256条；丢弃计数与存储错误可在报告中看到。采集与播放统计按周期输出，不记录每个包，不录制音频。

启动时保存最多8条ApplicationExitInfo，区分OEM电量管理、低内存、用户清理和异常崩溃。系统强停时App无法自行执行日志清理或恢复；下一次打开后的系统退出记录是关键证据。

Shizuku采集进程通过有界IPC队列报告事件和统计，由普通应用持久保存；不创建全局可读文件。导出设置采用字段白名单，不导出认证密钥、自动化令牌或配对URI，日志写入前脱敏。包身份、型号、端口/局域网地址可能出现在报告，分享前可自行检查。

观察字段：CAPTURE_READ_ERROR、SENDER_FAILED、SENDER_REMOTE_IDLE、SENDER_HOST_DESTROY、TASK_REMOVED、OFF_INFERRED_FROM_EMPTY_TASKS、AUTO_CONNECT_TIMEOUT/REJECTED、RECEIVER_SESSION_END、NETWORK_STATS、PLAYOUT_STATS、CAPTURE_STATS。它们区分主进程退出、发送桥失败、服务生命周期和播放故障。

本次同时修复自动连接dataSync超时/配额拒绝导致崩溃，并将静音保活与真正的PCM供给分开，避免对正常静音作欠载恢复。不能据此声称OEM杀进程或全部长期音质故障已解决。真机跨夜证据仍是验收要求。

test20 增加只读后台导出：已授权 ADB shell 使用 `content read --uri content://io.github.mu23xr.audiobridge.test.diagnostics/bundle` 获取相同白名单 ZIP。在电脑上用 `adb exec-out` 以二进制保存输出并校验 ZIP；不要用会重新编码文本的重定向方式。该 Provider 同时需要平台 DUMP 权限和 shell UID（或应用自身 UID），不授予普通应用读取权限。快照仅位于私有 cache，读取关闭时清理，下次导出也会清理残留；不自动创建 Download 下的公开日志。

新增 SOURCE_SOCKET_READY、SOURCE_HANDSHAKE_REPLY、SOURCE_ENDPOINT_REJECTED 和 SOURCE_RETRY_SCHEDULED，用于区分回包、源校验与重试等待。被拒绝端点只采样记录，详细地址仍属于私有证据，不放入公开 Issue。HOST_DESTROY_KEEP_INTENT 与 TASK_REMOVED 区分系统销毁和明确用户退出。CAPTURE_STATS 分别统计 zeroReads、silentBuffers、lastPositiveReadAgeMs、wouldClipSamples、limitedSamples；普通后端还记录 mixClippedSamples。软峰值保护不能恢复已发生的内部音频/麦克风相加削波，限幅计数不代表所有音质原因。
