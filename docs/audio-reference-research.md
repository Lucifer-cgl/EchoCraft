# 收音与降噪参考调研（2026-10-22）

本轮只检查公开仓库、源码和官方文档，不修改运行算法，不烧录。以下是代码阅读结论，尚未在 EchoCraft 上移植或比较音质。

## 1. 先验证采样，再比较降噪

- atomic14/esp32-i2s-mic-test：https://github.com/atomic14/esp32-i2s-mic-test
  - 已读 README：强调最小化采样测试，先区分麦克风、代码与其他环节的问题；它不是成熟降噪器。
- ikostoski/esp32-i2s-slm：https://github.com/ikostoski/esp32-i2s-slm
  - 已读 esp32-i2s-slm.ino：MIC_BITS=24，MIC_CONVERT 按容器位宽提取有效位；包含直流阻断器、INMP441 频响补偿和声级标定。
  - 声级计的 A/C 计权用于测量，不能作为音乐降噪直接照搬；滤波系数按 48 kHz 设计，不能原样用于当前 32 kHz。
  - 当前 EchoCraft 的 (raw >> 8)/8388608 形式与提取高位 24-bit 的思路一致，未据此发现移位错误。实际 I2S 时序、有效位及硬件噪声仍需要原始数据/实测确认。

## 2. ESP32-S3 官方语音路线

- 仓库：https://github.com/espressif/esp-sr
- AFE：https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/audio_front_end/README.html
- NSNet：https://docs.espressif.com/projects/esp-sr/en/latest/esp32s3/nsnet/README.html
- 已读 test_apps/esp-sr-afe/main/test_afe.cpp、include/esp32s3/esp_nsn_iface.h。
- 初始化模型，通过 get_samp_chunksize 查询块长，再 feed/fetch 或 process 获取结果；采样率、缓冲块大小需适配，不能直接塞入现有每块 256 点的循环。
- 官方 NSNet 文档列出 nsnet2 支持 S3、16 kHz PCM16、时频掩码。nsnet3 的主要实测平台是 P4/S31，不能把其性能指标当成 S3 实测结果。
- AFE 同时有 NS、AGC、VAD 等独立模块。AGC 是音量动态控制，不等于识别目标声源。
- 主要面向语音。用于乐器录音是否损伤延音、泛音需专门验证；16 kHz 模型不宜直接作为当前宽频音乐默认方案。
- 公开接口/示例不等于全部模型实现都公开源码，移植时需逐项检查版本、许可、二进制与模型依赖。

## 3. RNNoise：抑噪与周期性成分增强

- 仓库：https://github.com/xiph/rnnoise
- 已读 src/denoise.c，重点 rnnoise_process_frame、rnn_pitch_filter。
- 提取频带能量、音高与相关性特征；神经网络预测频带增益；音高滤波器加入与周期性相关的成分并重新归一化能量；平滑增益后重建音频。
- 这比固定乘三倍/五倍多了“选择保留什么”的估计过程，但不会测量声音距离。
- 可作为电脑端人声对照。移植 S3 的速度、内存、模型版本和采样率适配均未验证；不能承诺直接运行或自动保留所有乐器。

## 4. DeepFilterNet：电脑端语音增强对照

- 仓库：https://github.com/Rikorose/DeepFilterNet
- 已读 README 和 DeepFilterNet/df/deepfilternet3.py。
- 模型包含频带掩码与深度滤波分支，结合跨帧信息估计干净语音；项目定位全频带 48 kHz 语音增强。
- 适合先在电脑上比较，不直接承诺部署 ESP32-S3；预训练目标是语音，不代表通用音乐增强。

## 对 EchoCraft 的建议

1. 保存未经 DSP 的原始采样，测量安静段噪声、目标电平、削波及数据连续性。先定位录音链路问题，不用算法掩盖采样错误。
2. 相同素材比较原声、现有算法、成熟语音算法；比较前匹配输出响度，避免把更响误判为更清晰。
3. 分别测试说话、钢琴/吉他、风扇、椅子摩擦，以及噪声与目标同时发生的素材。
4. 若需要增强目标，先估计目标成分，再抑噪/适度增强并控制峰值；整段放大只会同时放大噪声。
5. 通用音乐模式与人声模式分开评估。现有单麦克风不提供距离标签，不能实现指定 15 cm 的空间增益曲线。

本轮未下载完整仓库、未安装模型、未改变五倍增益或固件。后续应固定参考提交版本再做可复现的对比实验。
