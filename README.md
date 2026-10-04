<div align="center">

# AudioBridge

**Android + Desktop 局域网低延迟音频串流**

一个统一维护的跨平台音频串流项目。Android 与 Desktop 共用 WFAS 协议，可互相作为发送端或接收端。

</div>

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

后续版本统一采用 **Pre-release → Release 原地转正**：

1. 在 Actions 的发布工作流中选择 `prepare`，填写最终版本号（例如 `1.4.0`）和源码引用。
2. GitHub Actions 在固定环境中测试、编译并签名 Android APK，同时构建 Windows 便携 ZIP。
3. 两端产物**直接上传到同一个 GitHub Pre-release**，不再用 Actions Artifact 长期保存或中转。
4. 测试通过后，在同一个发布工作流选择 `promote`，将现有 Pre-release 直接转为正式 Release；**不会重新编译，也不会替换原二进制文件**。
5. 如果候选版有问题，不覆盖已有版本，修复后使用新的版本号重新发布。

旧 Pre-release 和正式 Release 均保留，不做自动清理。

### Stable #153 签名说明

历史 `AudioBridge Stable 153` 使用一套**独立历史签名**。它仅用于保留和识别该历史版本，后续测试版和正式版均不再沿用这套签名，也不以它作为未来升级/发布基线。Stable #153 的签名链和相关历史资源单独保留。

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

## 项目来源

Android 与 Desktop 均基于 Marco Morosi 的 WiFi Audio Streaming 项目继续开发。

本仓库自 2026 年 9 月起作为统一的独立维护版本继续演进，并保留原有版权、许可证和第三方声明。

## License

本项目继续遵循 **European Union Public Licence v1.2 (EUPL v1.2)**。各子项目中的第三方组件仍遵循各自许可证。
