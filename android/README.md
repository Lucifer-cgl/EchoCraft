# EchoCraft Android Studio

原生 Android 调试版：Java + Camera2 + MediaCodec/MediaMuxer + C++ DSP，界面为暖白、深绿与橙色录制状态。Android 8 以上，当前打包 arm64-v8a；主要实机为 Redmi Note 11 Pro / Android 13。

## 能力与保存方式

- 相机预览、H.264 画面与 AAC 音频合成为 MP4。
- v0.2.0 新增纯录音 MP3（32 kHz 单声道、128 kbps），不打开相机；正常录制均由用户手动开始、结束。
- MP3 支持播放、再次降噪、音量编辑，与视频共用作品库、重命名及删除同步。
- 默认开启实时降噪，录制中可切换；EchoCraft 数据通路由板上切换，标准 USB 麦克风由 App 实时 DSP 处理，避免双重处理。
- 普通录制必须使用外置输入，没有外置来源时拒绝录制，不使用手机内置麦克风替代。
- 每次录制独立目录：`Android/data/com.echocraft.studio/files/Movies/EchoCraft/时间-标识/`，包括 `original.mp4`、`session.json` 和后续编辑版本。
- 作品库只索引带 App 记录的目录，不扫描系统相册。删除时确认并实际删除本次录像、所有编辑版本与记录。
- 历史视频可再降噪、调节音轨音量至 0～300%，生成新 MP4，原版本保留。视频码流复制，音频重新编码；不调用手机音量设置。
- Android 10+ 公共导出：视频在 `Movies/EchoCraft/作品名称/`，MP3 在 `Music/EchoCraft/作品名称/`。App 删除同步删除公共文件，文件管理或相册删除后返回 App 同步记录，不自动恢复已删除文件。应用专属文件在卸载时由 Android 删除，已导出的公共文件保留。
- 录制资源释放完成后再恢复操作；USB 停止时清理残留数据并释放接口。电脑中转的录制后删除再录制检查已连续三次通过；不同机型直连仍需验证。
- MP3 使用动态链接 LAME 3.100，详见 `THIRD_PARTY_NOTICES.md`。

## 当前电脑中转调试

接线：开发板 **COM** 接电脑，手机 USB 接电脑，麦克风原接线保留。无需无线网络。

1. 板上烧录 `firmware/mic-test` 的 `mic-usb-app` 环境（包含连续串口包和原生 USB 通路）。
2. 手机开启 USB 调试并授权电脑，安装时允许 USB 安装。
3. 在项目目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File android/start-debug-bridge.ps1 -ComPort COM9 -Install
```

已安装时省略 `-Install`。脚本添加 `adb -d reverse tcp:28732 tcp:28732`，启动只监听电脑 `127.0.0.1` 的中转服务，再打开 App 的电脑中转模式。
App “设备诊断”中可切换“电脑中转调试”。录制时明确显示 `EchoCraft Mic · 电脑中转调试`。
音频协议包含序号、样本计数、模式、I²S 溢出标志和 CRC32；丢包或校验失败会终止录制，不会补入手机麦克风。
电脑中转服务每次流单独保存到项目 `recordings/bridge-时间/`，包含 `device-audio.wav` 与报告。电脑录音窗口和中转服务不能同时占用 COM9。

## 后续手机直连测试

停止当前录像，关闭 App “设备诊断 → 电脑中转调试”。用双 Type-C 线将手机接开发板 **USB** 口，允许访问 EchoCraft USB 设备。
App 会优先使用自定义 USB CDC PCM 通路；也可选择系统识别的标准 USB 麦克风，并在 AudioRecord 开始后核对实际路由。
“检测到设备”不代表音频通过验证。先用设备诊断的“测试收音（2秒）”，再录像。当前自定义原生 USB 通路仍待端到端实机排查，不能宣称已支持系统相机的标准 UAC 输入。

## 构建

```powershell
android/gradlew.bat -p android assembleDebug
adb -d install -r android/app/build/outputs/apk/debug/app-debug.apk
```

AGP 9.1.1、Gradle 9.3.1、SDK 35、NDK 27.2.12479018、CMake 3.22.1。`local.properties` 指向本机 SDK（不提交），当前为 `E:/android-sdk`。
PCM DSP 共用 `firmware/mic-test/src/realtime_denoiser.h`。离线处理会补偿 DSP 缓冲延迟，保持解码后的 PCM 帧数；AAC 重编码可能有一个编码帧量级的封装差异。

## 调试信息

相机或录音权限必须允许。MIUI 可能出现运行时权限显示已授予、但相机 AppOps 被设为 ignore 的情况，此时应由用户在系统权限管理中允许，不绕过系统策略。
设备诊断可复制最近错误；原生 USB 短测试区分识别、授权、收包、CRC 与缓冲溢出。相机/音轨缺失不会登记为成功作品。
Debug APK 内部测试入口可使用测试缓存；临时手机内置麦克风验证仅限显式 ADB 测试参数，不在正常录制入口开放，测试缓存不进入作品库。

待完成的端到端验收：直连 USB 采集、断线保存、不同机型路由和长时音画同步。当前不能把电脑中转通过等同于手机直连已通过。

## 2026-10-22 电脑中转实机验收

Redmi Note 11 Pro 经 USB 调试接入电脑，ESP32-S3 经 COM9 接入电脑。实际录制 5.056 秒板端音频，632 个数据包，无序号错误、CRC 错误或板端溢出。录制中关闭再开启降噪，收到 193 个原声包与 439 个降噪包。

手机生成 H.264/AAC MP4，并完成历史视频降噪、50% 音量导出。导出前后压缩视频流 SHA256 一致；50% 版本解码音频 RMS 比为 0.4988，系统音量保持 6。PCM 帧数、静音、作品库过滤和删除全部版本检查通过。

首个视频关键帧在音频开始后约 241 毫秒出现，音视频结束时间相差约 4 毫秒。尚未进行拍手对齐的物理同步测量或长时间录制测试。验收视频与报告位于 `qa/bridge-acceptance/`；板端独立 WAV 位于项目的 `recordings/bridge-20261022-163214-046008/`。

## 公共视频与作品库同步

Android 10 及以上，新录像和编辑版本通过 MediaStore 写入 `Movies/EchoCraft/作品名称/`。作品库卡片的“重命名”同时修改 App 私有目录与公共视频目录；拒绝重名及路径特殊字符。

返回 App 或打开作品库时核对已发布视频。从相册删除某个版本，也会删除对应私有副本；全部版本删除后移除作品记录。App 删除作品则同时删除公共视频和所有私有版本，并清理空目录。停止启动时自动导出历史副本，已删除视频不会被自动恢复。会尝试清理旧版本遗留在 `Movies/EchoCraft` 下的空目录，保留非空目录。

红米实机使用独立测试素材验证了公开发布、双目录重命名、相册删除不恢复、App 删除公共视频及空目录清理。构建和 lint 检查通过。系统相册“删除”通常先进入回收站；回收站内的保留期限由系统管理。
