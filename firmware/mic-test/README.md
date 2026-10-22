# EchoCraft 最小采样诊断

这是串口采样诊断程序，不是 USB 麦克风固件，不做降噪。

另有 `mic-record` 环境：921600 波特率，32 kHz 单声道 16-bit PCM，收到 `R` 命令后采集 10 秒。降低采样率是为了给串口传输留出带宽；它仍不是 USB Audio Class 设备。

```powershell
.\.venv\Scripts\python.exe -m platformio run -d firmware/mic-test -e mic-record -t upload --upload-port COM9
.\.venv\Scripts\python.exe tools/record_serial.py --output recordings/new-take.wav
```

录音脚本生成原始 WAV、仅提高音量的 `-listen.wav` 和参数 JSON。不会覆盖已有同名 WAV。录音版本请勿使用下面的 115200 波特率电平读取脚本。

接线：VDD→3V3、GND→GND、L/R→GND、SD→GPIO15、WS→GPIO17、SCK→GPIO18。
通过 COM 接口连接电脑。48 kHz、32-bit I²S 时隙，读取左声道的 24-bit 数据。
第一版不启用 PSRAM；16 MB Flash 配置需要通过实物检测确认。

在项目根目录 PowerShell 执行：

```powershell
.\.venv\Scripts\python.exe -m platformio run -d firmware/mic-test
.\.venv\Scripts\python.exe -m platformio run -d firmware/mic-test -t upload --upload-port COM9
.\.venv\Scripts\python.exe -m platformio device monitor -p COM9 -b 115200
```

COM9 是此前检测结果，烧录前重新确认设备。烧录会替换出厂程序，应先备份 Flash。
观察每秒打印的 RMS 和 peak；对麦克风说话应出现变化。全零、削波或稳定异常值需要进一步排查，不能仅据非零数据判定音质合格。

参考：https://docs.espressif.com/projects/esp-idf/en/v4.4.2/esp32s3/api-reference/peripherals/i2s.html
