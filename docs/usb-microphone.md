# 标准 USB 麦克风试验

`mic-usb-app` 是原有自定义 CDC PCM 固件。`mic-usb-uac` 在同一 USB 设备上增加标准 UAC1 音频输入，保留 CDC 接口供 EchoCraft App 使用。两个环境当前均使用固定五倍增益及默认板端降噪；下列 Windows 初次采集是在此前三倍增益版本下完成。

系统设备名为 **EchoCraft 智能收音系统**，USB 产品字符串含 EchoCraft 前缀供现有 App 识别。标准音频格式为 32 kHz、16-bit、单声道，异步等时 IN。USB 串号目前是原型固定标识，不适用于多台设备同时部署。

## 当前验证范围

- Windows 系统音频驱动加载成功，系统音频输入和 DirectShow 可发现该麦克风。
- 仅开发板 USB 口接电脑、COM 口断开：10 秒采集得到 319744 帧 / 9.992 秒 PCM，非全零。
- 同一单线接法下，旧 App 使用的 CDC PCM 通道 100 包序号与 CRC 校验通过，无包内溢出标志。
- 用户反馈手机自带相机仍录出无声视频；Android 系统相机兼容性**尚未通过**。不能把 Windows 通过当成 Android 已通过。
- 用户反馈 COM 连电脑且 USB 连手机时影响 App 录制，移除 COM 后可录。原因尚未定位；手机测试只使用 USB 单线，避免混入串口自动复位及双电源因素。

## 红米读取失败排查

Redmi Note 11 Pro 的日志确认系统已登记 USB 音频输入（hasInput=true），系统相机尝试使用 USB 路由；USB HAL 能识别实际 32 kHz 单声道并准备转换为相机所需的 48 kHz 双声道。随后 USB 读取线程反复报告 `proxy_read fail`，相机停止报 `MediaRecorder stop failed: -1007`。这不能归因于音量过小，也不应直接归因于权限。

发现旧 DCD 没有实现单端点关闭：主机停止轮询后，待处理的等时传输可能保持 busy。新增仅针对本麦克风 IN 端点的关闭实现，停止端点、清理其 FIFO，再由 TinyUSB 清除 busy/claimed 状态，供下一次 SET_INTERFACE 启动。修复已编译并烧录；红米相机的重复开始/停止验证仍待反馈。

## 测试接法

日常使用：开发板 USB → 手机，仅一根线。COM 用于电脑烧录与串口诊断。串口工具可能改变 DTR/RTS 并复位开发板；不要在录制过程中打开烧录工具。

标准 USB 音频由系统音频策略选择，不属于蓝牙设备，不保证手机设置里有统一的“麦克风选择”菜单。开发者选项的“停用 USB 音频自动转接”应关闭。手机自带相机是否使用 USB 输入仍需具体机型测试。

当前 EchoCraft App 只管理本 App 拍摄的作品，尚不支持导入系统相机拍摄的视频做后期；标准 USB 输入成功后，若希望 App 仅作为可选精修工具，还需补充导入功能。

## 实现注意事项

该 Arduino 核心的 TinyUSB 音频类未启用，试验使用应用类驱动钩子实现 UAC1。IAD 内 AC/AS 接口及备用设置必须在一次 open 中完整声明给驱动；ESP32 旧 DCD 需要选择下一个 USB 帧奇偶，否则会出现每两毫秒只发送一次、音频数据约减半的问题。仅做原型验证，仍需检查长期时钟漂移、休眠恢复、反复开始停止和不同安卓主机。

串口发送 `U` 可输出 UAC 活跃状态、包数、欠载、溢出、发送错误及描述符。诊断日志和采样仅保存在被 Git 忽略的本地目录。

参考：[Android USB 数字音频](https://source.android.com/docs/core/audio/usb)、[TinyUSB 类驱动接口](https://github.com/hathach/tinyusb/blob/0.14.0/src/device/usbd_pvt.h)。
