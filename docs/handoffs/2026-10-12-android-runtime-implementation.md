# Android运行日志与故障修复实施

用户明确授权改进日志、查明原因并修复。#32持久诊断、#33自动连接崩溃，关联#4/#5/#14；在独立runtime-fix工作树/分支fix/android-runtime-diagnostics实现。已同步远端test17 @68779ab（七个CI提交），原桌面工作树修改未混入。

实现：私有日志8MiB上限、256条写入队列、启动清理7天前文件、旧退出原因、写入前脱敏、用户SAF导出白名单报告；有界Shizuku IPC事件；周期采集/网络/播放数据包含削波、欠载、丢帧、缓冲和速率；角色/服务/会话结束事件。记录不包含音频内容或凭证。

已定位并修正：AutoConnectService dataSync超时无onTimeout、onCreate/start请求拒绝未捕获；新增立即停止、非sticky失败及安全启动入口，独立mediaPlayback会话不因发现服务超时被主动关闭。另有静态可复现实验：正常静音包更新lastAudioAt使watchdog误判flowing；新增实际非零PCM供给窗口用于单播/多播健康判断。

验证阶段：最终43单元测试全部通过、lintDebug0错误（217 warnings、20 hints）、签名preview及AndroidTest APK构建/lintVital通过。最终1.3.1-test.18/code610120941两台升级成功，正式test16均保留。SHA256=FCCEDDCE7A0EBCC17EC9DB8CF732700D0CFE082E0A22882F186F272FF4779C3D。APK在忽略目录out/AudioBridge-1.3.1-test.18-preview.apk；私钥仅子进程环境，临时JKS自动删除，永久签名4DDF…FB63未更换。此前610120919/931为阶段产物。

真机：平板17:39尝试发送启动失败，AudioPolicy三策略sink均null，REMOTE_SUBMIX路由等待超时，当前音乐设备bt_a2dp(0x80)，无残留UserService。新增日志没有改动该路由条件；根因尚未确定，不当作昨夜断连原因。已补失败销毁前同步抽取IPC日志、每个策略错误与实际设备掩码，最终构建覆盖。

阻塞与下一步：两台锁屏；AndroidTest助手安装被系统取消，已经请求用户解锁，未绕过限制。设备安装本体已成功，但真机导出跨进程留存、实际PCM/静音恢复/后台/角色测试、长期音质、OEM防护及系统触发dataSync超时未验收；没有启动持续音频监控。解锁后先重发助手并各运行两次，再做SAF导出和原设置的平板→手机传输，至少180秒静音后恢复，复查路由现场。第一轮6fc4c2f四项CI通过；追加提交推送后核对CI。Draft PR #35已附到任务，所有运行Issue保留open，不发布stable。
