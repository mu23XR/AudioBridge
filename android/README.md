# AudioBridge for Android

AudioBridge 是面向 Android 的局域网低延迟音频桥接工具，可作为发送端或接收端使用。

当前 Android 主路线是 **App + Shizuku 系统音频桥**：不 Root，默认不依赖 MediaProjection 屏幕共享授权。MediaProjection 仅作为可选兼容路线保留。

## 当前能力

- Android -> Android WFAS 低延迟音频
- App + Shizuku 系统音频抓取
- 发送端 / 接收端角色切换
- 息屏与后台持续运行
- 临时网络波动恢复
- 静音状态保活
- 清理最近任务时主动退出发送并释放音频路由
- 独立发送/接收音量控制
- RTP / HTTP / DLNA / Snapcast
- QR 配对：`audiobridge://pair?... `
- 可选认证与加密
- 简体中文 / English

## 当前开发状态

正式集成分支：`main`

未解决问题、正在验证的 Bug 和功能开发统一记录在 GitHub Issues：

- https://github.com/mu23XR/AudioBridge/issues
- Android 稳定性跟踪：#6
- 仓库治理跟踪：#7

聊天记录不是任务真源。

## 安装与发布

正式和测试构建统一使用 AudioBridge 的永久包名与签名身份：

- Application ID: `io.github.mu23xr.audiobridge`
- Releases: https://github.com/mu23XR/AudioBridge/releases

历史 Stable #153 / test.11 属于旧包名/旧签名线，不再作为未来发布基线。

## 构建

需要 JDK 17 与 Android SDK。

```bash
git clone https://github.com/mu23XR/AudioBridge.git
cd AudioBridge/android
./gradlew :app:assembleDebug
```

CI 会运行单元测试、Android lint 和 APK 编译。

## 协议

Android 与 Desktop 共用根目录的 WFAS 规范：

[../WFAS_PROTOCOL.md](../WFAS_PROTOCOL.md)

协议不兼容修改必须同步更新两端。

## 项目历史

AudioBridge 的早期代码和设计曾参考并演进自 Marco Morosi 的开源 WiFi Audio Streaming 项目。当前仓库已经作为独立项目持续开发，产品入口、发布、签名、Android Shizuku 音频路线和 Desktop 版本均由本仓库独立维护。

原始版权/许可证声明以及第三方许可证信息按许可证要求保留在源码和许可证文件中；运行界面不再展示原项目的赞助、下载或产品入口。

## License

本项目按仓库中的 EUPL v1.2 许可文件发布。

- [LICENSE.md](LICENSE.md)
- [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)
