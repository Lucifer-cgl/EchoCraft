# EchoCraft

软硬件协同的模块化收音系统：硬件负责近距离采集与轻量处理，Android 软件负责视频拍摄、声音精修和作品管理。

[下载 Android APK](https://lucifer-cgl.github.io/EchoCraft/download/) · [GitHub Release](https://github.com/Lucifer-cgl/EchoCraft/releases/tag/v0.1.0) · [加速下载 APK](https://gh-proxy.com/https://github.com/Lucifer-cgl/EchoCraft/releases/download/v0.1.0/EchoCraft-debug.apk)

## 当前功能

- ESP32-S3 + INMP441，32 kHz 单声道 PCM，轻量实时频谱降噪与原声切换。
- Android 相机画面与外置硬件声音合成为 MP4；正常录制由用户点击开始、结束，不以手机麦克风静默代录。
- 默认开启实时降噪，可在录像中切换；历史视频可再次降噪、修改音轨音量，保留原版本。
- App 专属作品库，Android 10+ 同步到 `Movies/EchoCraft/作品名称/`。
- 作品重命名同步修改文件夹。App 删除同时删除公共视频；返回 App 或打开作品库时同步相册中的删除，不自动补回；清理空文件夹。

当前为 **Android ARM64 开发预览版（debug 签名）**。电脑中转路径已实机验证；手机直连收到用户测试通过反馈，跨机型、长时间同步和断线行为仍需继续验证。降噪是轻量 DSP，不是 AI 音源分离，也不能可靠地仅凭单麦克风判断声源距离。

## 下载与网络选择

下载页识别设备类型，以 GitHub 的网络连通性优先选路：可连接时走原站（海外或可用代理），连接失败时切换第三方 GH-Proxy 加速；两条线路均可手动选择。浏览器无法可靠读取代理开关或精确判断所在地，时区与语言只作环境提示，不承诺识别所有网络。加速线路不保证长期可用。

APK 作为 **Release 附件**提供，不放进 Git 源码历史。Release 同时提供 `SHA256SUMS.txt`。网页显示同一个哈希值；需要自动验证时，在克隆的源码目录运行：

```powershell
python tools/download_apk.py
# 强制线路：--route direct 或 --route mirror
```

下载脚本优先原站、失败切加速，并在保存 APK 前核对 SHA256。下载页本身使用 GitHub Pages；如该页面无法打开，可直接用上面的加速链接或下载脚本。

## 开发

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
# 安装 Android SDK，设置 JAVA_HOME 与 ANDROID_HOME；或创建本机 android/local.properties
android/gradlew.bat -p android assembleDebug lintDebug
```

Android 工具链：AGP 9.1.1、Gradle 9.3.1、SDK 35、Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1；本机验证 JDK 25。需支持 Android 8.0+，公共视频同步需要 Android 10+。

固件烧录（自行确认 COM 号）：

```powershell
.venv/Scripts/python.exe -m platformio run -d firmware/mic-test -e mic-usb-app -t upload --upload-port COM9
```

[接线说明](hardware/README.md) · [Android 说明](android/README.md) · [降噪说明](docs/board-denoise.md)

另有标准 USB 麦克风试验环境 `mic-usb-uac`：电脑系统音频采集已通过，但手机自带相机仍有无声反馈，尚未作为默认固件。详见 [USB 麦克风状态](docs/usb-microphone.md)。

## 仓库结构与提交范围

| 目录 | 内容 |
| --- | --- |
| `firmware/` | ESP32-S3 固件与共享 DSP |
| `android/` | Android App 源码、Gradle Wrapper、电脑中转启动脚本 |
| `tools/` | 录音、离线处理、电脑中转与下载工具 |
| `hardware/` | 接线说明 |
| `docs/` | 技术说明与静态下载入口 |

`.gitignore` 排除虚拟环境、构建缓存、SDK 本机配置、录音、测试结果、固件备份、APK 与个人视频。保留 Gradle Wrapper JAR 和一个 1.7 KB 人工生成的黑屏测试夹具，它们用于构建和显式调试检查，不包含私人录音。已有本地文件不删除、不搬迁。
