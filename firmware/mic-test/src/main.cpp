#ifdef ECHOCRAFT_DENOISE
#include "denoise_main.h"
#else
#include <Arduino.h>
#include <driver/i2s.h>
#include <math.h>

// Wiring: INMP441 SD=15, WS=17, SCK=18, L/R=GND, VDD=3V3.
// Diagnostic only: UART statistics, not a USB microphone firmware.
static constexpr i2s_port_t PORT = I2S_NUM_0;
static int32_t samples[256];
static void check(esp_err_t result) {
  if (result == ESP_OK) return;
  Serial.printf("I2S ERROR: %s\n", esp_err_to_name(result));
  while (true) delay(1000);
}

void setup() {
#ifdef ECHOCRAFT_RECORD
  Serial.begin(921600);
#else
  Serial.begin(115200);
#endif
  delay(1500);
  Serial.println("EchoCraft microphone diagnostic: 48000 Hz, left channel");
  i2s_config_t config = {};
  config.mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_RX);
#ifdef ECHOCRAFT_RECORD
  config.sample_rate = 32000;
#else
  config.sample_rate = 48000;
#endif
  config.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  config.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  config.intr_alloc_flags = 0;
  config.dma_buf_count = 8;
  config.dma_buf_len = 256;
  config.use_apll = false;
  check(i2s_driver_install(PORT, &config, 0, nullptr));
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = 18;
  pins.ws_io_num = 17;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = 15;
  check(i2s_set_pin(PORT, &pins));
  Serial.println("Speak near the microphone. RMS/peak should change; all-zero data suggests a wiring/channel issue.");
}

void loop() {
#ifdef ECHOCRAFT_RECORD
  if (Serial.read() != 'R') { delay(1); return; }
  size_t received = 0;
  // Discard stale DMA audio before beginning a fresh recording.
  for (int i = 0; i < 12; ++i)
    check(i2s_read(PORT, samples, sizeof(samples), &received, pdMS_TO_TICKS(1000)));
  Serial.print("ECPCM1\n");
  uint32_t remaining = 32000 * 10;
  int16_t pcm[256];
  while (remaining) {
    size_t requested = min(remaining, uint32_t(256)) * sizeof(int32_t);
    if (i2s_read(PORT, samples, requested, &received, pdMS_TO_TICKS(1000)) != ESP_OK || !received)
      { Serial.print("READ_FAILED"); return; }
    size_t frames = received / sizeof(int32_t);
    for (size_t i = 0; i < frames; ++i) pcm[i] = int16_t(samples[i] >> 16);
    Serial.write(reinterpret_cast<uint8_t*>(pcm), frames * sizeof(int16_t));
    remaining -= frames;
  }
  Serial.print("\nEND\n");
  return;
#else
  static uint32_t last = millis();
  static uint32_t count = 0, nonzero = 0, clipped = 0;
  static double sum = 0, squares = 0, peak = 0;
  size_t bytes = 0;
  esp_err_t result = i2s_read(PORT, samples, sizeof(samples), &bytes, pdMS_TO_TICKS(1000));
  if (result != ESP_OK || bytes == 0) {
    Serial.printf("READ ERROR result=%s bytes=%u\n", esp_err_to_name(result), unsigned(bytes));
    delay(100);
    return;
  }
  for (size_t i = 0; i < bytes / sizeof(int32_t); ++i) {
    // INMP441: signed 24-bit sample in the high 24 bits of a 32-bit slot.
    int32_t value = samples[i] >> 8;
    double normalized = double(value) / 8388608.0;
    sum += normalized;
    squares += normalized * normalized;
    peak = fmax(peak, fabs(normalized));
    nonzero += value != 0;
    clipped += fabs(normalized) >= 0.99;
    ++count;
  }
  if (millis() - last >= 1000) {
    double mean = sum / count;
    double rms = sqrt(fmax(0.0, squares / count - mean * mean));
    Serial.printf("samples=%lu nonzero=%lu rms=%.1f dBFS peak=%.1f dBFS dc=%.6f clipped=%lu\n",
      (unsigned long)count, (unsigned long)nonzero,
      20 * log10(fmax(rms, 1e-12)), 20 * log10(fmax(peak, 1e-12)), mean, (unsigned long)clipped);
    last = millis();
    count = nonzero = clipped = 0;
    sum = squares = peak = 0;
  }
#endif
}
#endif // ECHOCRAFT_DENOISE
