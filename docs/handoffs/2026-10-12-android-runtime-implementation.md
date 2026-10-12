# Android运行日志与故障修复实施

用户明确授权改进日志、查明原因并修复。#32持久诊断、#33自动连接崩溃，关联#4/#5/#14；在独立runtime-fix工作树/分支fix/android-runtime-diagnostics实现。已同步远端test17 @68779ab（七个CI提交），原桌面工作树修改未混入。

实现：私有日志8MiB上限、256条写入队列、启动清理7天前文件、旧退出原因、写入前脱敏、用户SAF导出白名单报告；有界Shizuku IPC事件；周期采集/网络/播放数据包含削波、欠载、丢帧、缓冲和速率；角色/服务/会话结束事件。记录不包含音频内容或凭证。

已定位并修正：AutoConnectService dataSync超时无onTimeout、onCreate/start请求拒绝未捕获；新增立即停止、非sticky失败及安全启动入口，独立mediaPlayback会话不因发现服务超时被主动关闭。另有静态可复现实验：正常静音包更新lastAudioAt使watchdog误判flowing；新增实际非零PCM供给窗口用于单播/多播健康判断。

验证阶段：第一轮43单元测试全部通过、lintDebug0错误、assembleDebug通过。第二轮签名preview及AndroidTest APK构建、unit/lint/lintVital通过，版本1.3.1-test.18/code610120919（尚未安装的阶段产物）。追加脱敏JSON边界后需要最终构建核对。私钥仅子进程环境，临时JKS自动删除；没有换永久签名。

尚未验证：真机导出跨进程留存、设备安装、长期音质和原始发送异常、实际OEM退出防护。不要关闭运行问题或发布stable。下一步最终构建/验签、升级两个测试包并运行导出/重启测试，再按平板发送手机接收抓取现场。
