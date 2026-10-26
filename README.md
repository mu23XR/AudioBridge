<div align="center">

# AudioBridge

**Android + Desktop 局域网低延迟音频串流**

一个统一维护的跨平台音频串流项目。Android 与 Desktop 共用 WFAS 协议，可互相作为发送端或接收端。

</div>

## 项目状态与协作

- [Open Issues](https://github.com/mu23XR/AudioBridge/issues)：当前 Bug / 功能任务的唯一正式任务列表
- [Android 稳定性跟踪 #6](https://github.com/mu23XR/AudioBridge/issues/6)：下一稳定版前的阻塞问题
- [仓库规范化跟踪 #7](https://github.com/mu23XR/AudioBridge/issues/7)：分支保护、CI、发布与治理剩余项
- [CONTRIBUTING.md](CONTRIBUTING.md)：开发与 PR 规则
- [SECURITY.md](SECURITY.md)：安全问题报告方式

聊天记录不是任务真源；未解决工作应记录在 GitHub Issues。

## 仓库结构

| 目录 | 内容 |
| --- | --- |
| `android/` | Android 客户端/发送端，支持 Shizuku 系统音频桥、MediaProjection 兼容路线、WFAS/RTP/HTTP/DLNA/Snapcast |
| `desktop/` | Desktop 客户端/发送端，基于 Kotlin + Compose Desktop，包含 Windows 原生系统音频抓取引擎 |
| `WFAS_PROTOCOL.md` | Android 与 Desktop 共用的 WFAS 协议规范 |

## 当前重点

### Android

- Android ↔ Android 低延迟 WFAS 音频
- Shizuku 无 Root 系统音频抓取
- HyperOS / Redmi 兼容处理
- 临时断网自动恢复
- 静音不误断
- 手动退出/清后台时真正断开
- 发送端与接收端独立音量控制

### Desktop

Desktop 代码已经并入本仓库，后续在这里直接维护，不再拆成另一个项目。

第一阶段以 **Windows 10/11 x64** 为主要目标：

- PC → Android：抓取 Windows 系统音频并通过 WFAS 发送
- Android → PC：PC 作为接收端直接播放
- 自动发现
- 本机继续播放
- 独立音量
- 断网自动恢复
- Windows 托盘
- 中文界面
- 便携包 / Windows 可执行发行包

原 Desktop 工程同时保留 Linux 与 macOS 架构，后续可以继续同步维护。

## 共用协议

两端以根目录的 [WFAS_PROTOCOL.md](WFAS_PROTOCOL.md) 为共同协议规范。

当前协议核心包括：

- WFAS v2
- UDP PCM
- 单播 / 组播
- 设备发现
- PING / PONG 保活
- 序号与 sample position
- 可选认证与 ChaCha20-Poly1305 加密

协议兼容性修改应同时检查 Android 与 Desktop。

## 统一发布

Android 与 Windows Desktop 共用同一个 GitHub Releases 页面。

创建严格的 `vMAJOR.MINOR.PATCH` 标签（例如 `v1.4.0`）后，统一发布流水线会：

1. 运行 Android 单元测试并构建带固定签名的 APK。
2. 构建 Windows Desktop 便携 ZIP。
3. 使用同一个版本号生成两端产物。
4. 将 APK 与 Windows ZIP 一起发布到同一个 GitHub Release。

这样 Android 与 Desktop 的应用内更新检查都会指向同一个版本源。

## 构建

### Android

```bash
cd android
./gradlew :app:assembleDebug
```

### Desktop

```bash
cd desktop
./gradlew createReleaseDistributable
```

Windows:

```powershell
cd desktop
.\gradlew.bat createReleaseDistributable packagePortableArchives
```

## 项目历史

AudioBridge 早期代码和设计曾参考并演进自 Marco Morosi 的开源 **WiFi Audio Streaming** 项目。

当前仓库已经作为独立的 AudioBridge 项目维护：Android Shizuku 音频路线、Desktop 客户端、包名、签名、发布、Issue/CI 和产品入口均由本仓库独立维护。

原始版权/许可证声明和第三方许可文本按许可证要求保留在源码及许可证文件中；原项目的赞助、下载和产品入口不属于 AudioBridge 的运行界面。

## License

本项目继续遵循 **European Union Public Licence v1.2 (EUPL v1.2)**。各子项目中的第三方组件仍遵循各自许可证。

## 2.0.1 正式版

Android 与 Windows 统一使用 2.0.1，从同一确定源码经根发布流程构建；下载及升级说明见 [2.0.1 发行说明](docs/releases/2.0.1.md)。包含多源接收、自适应／手动整数延迟、每源音量与可选远端音量同步，以及最新 test21 Android 与 vpn.9 PC 的恢复修正。

正式 Android 包名 `io.github.mu23xr.audiobridge`，独立测试包为 `.test`，开发包为 `.debug`。正式版可与测试版共存，权限和数据各自独立。子目录旧 README 中“测试包统一正式包名”的描述不适用于新的独立测试身份。Windows 正式版同样与测试版配置隔离。

发布包含 Android APK、Windows x64 ZIP／TAR.GZ、统一源码提交、provenance 和 SHA256SUMS。签名沿用 test16／1.3.1 永久身份。正式发布不代表所有厂商设备过夜和所有 VPN 配置均已验收，已知限制与持续跟踪的问题见发行说明。
