#pragma once
#include <Arduino.h>
#include <driver/i2s.h>
#include "realtime_denoiser.h"
#include "hum_reducer.h"
#ifdef ECHOCRAFT_NATIVE_USB
#include <USB.h>
static USBCDC appUsb;
static bool appStreaming=false;
static bool uartStreaming=false;
static uint32_t appSequence=0,appSampleIndex=0,appOverflowStart=0;
static uint32_t crc32Audio(const uint8_t* bytes,size_t size) {
  uint32_t crc=0xffffffff;
  for(size_t i=0;i<size;++i) {crc^=bytes[i];for(int b=0;b<8;++b)crc=(crc>>1)^(0xedb88320U&-(crc&1));}
  return ~crc;
}
#endif

static RealtimeDenoiser denoiser;
static HumReducer humReducer;
static QueueHandle_t eventQueue=nullptr;
static int32_t rawSamples[256];
static float inputFrame[256],outputFrame[256];
static int16_t pcmFrame[256];
static bool bypass=false;
// Applied after DSP in both raw and denoised output; the Android app stays unchanged.
static constexpr float outputGain=3.0f;
static uint32_t remaining=0,maxDspUs=0,overflows=0,readErrors=0;
static uint32_t capturedMaxUs=0,capturedOverflowStart=0,capturedErrorStart=0;

static void drainEvents() {
  i2s_event_t event;
  while(xQueueReceive(eventQueue,&event,0)==pdTRUE)
    if(event.type==I2S_EVENT_RX_Q_OVF || event.type==I2S_EVENT_DMA_ERROR) ++overflows;
}
static void processFrame() {
  uint32_t begin=micros();
  denoiser.process(inputFrame,outputFrame,bypass);
  for(int i=0;i<256;++i)outputFrame[i]=humReducer.process(outputFrame[i],!bypass);
  uint32_t elapsed=micros()-begin;
  maxDspUs=std::max(maxDspUs,elapsed);
  if(remaining) capturedMaxUs=std::max(capturedMaxUs,elapsed);
  for(int i=0;i<256;++i)
    pcmFrame[i]=int16_t(std::max(-32768.f,std::min(32767.f,std::round(outputFrame[i]*outputGain*32768))));
}

// Test-only digital replay exercises THIS board's algorithm against a known input.
static void replay() {
  i2s_stop(I2S_NUM_0);
  Serial.setTimeout(3000);
  uint32_t frames=0;
  if(Serial.readBytes(reinterpret_cast<char*>(&frames),4)!=4 || frames>32000*180 || frames%256) {
    Serial.println("REPLAY_ERROR");i2s_start(I2S_NUM_0);return;
  }
  denoiser.reset();humReducer.reset();maxDspUs=0;
  Serial.print("ECREPLAY1\n");
  for(uint32_t offset=0;offset<frames;offset+=256) {
    if(Serial.readBytes(reinterpret_cast<char*>(pcmFrame),512)!=512) {
      Serial.print("REPLAY_ERROR");break;
    }
    for(int i=0;i<256;++i) inputFrame[i]=pcmFrame[i]/32768.f;
    processFrame();
    Serial.write(reinterpret_cast<uint8_t*>(pcmFrame),512);
  }
  Serial.printf("\nREPLAY_END max_dsp_us=%lu\n",(unsigned long)maxDspUs);
  denoiser.reset();humReducer.reset();i2s_start(I2S_NUM_0);drainEvents();
}

void setup() {
  Serial.setTxBufferSize(2048);Serial.begin(921600);delay(500);
  i2s_config_t config={};
  config.mode=static_cast<i2s_mode_t>(I2S_MODE_MASTER|I2S_MODE_RX);
  config.sample_rate=32000;config.bits_per_sample=I2S_BITS_PER_SAMPLE_32BIT;
  config.channel_format=I2S_CHANNEL_FMT_ONLY_LEFT;
  config.communication_format=I2S_COMM_FORMAT_STAND_I2S;
  config.dma_buf_count=8;config.dma_buf_len=256;
  esp_err_t result=i2s_driver_install(I2S_NUM_0,&config,32,&eventQueue);
  i2s_pin_config_t pins={};
  pins.mck_io_num=I2S_PIN_NO_CHANGE;pins.bck_io_num=18;pins.ws_io_num=17;
  pins.data_out_num=I2S_PIN_NO_CHANGE;pins.data_in_num=15;
  if(result==ESP_OK) result=i2s_set_pin(I2S_NUM_0,&pins);
  if(result!=ESP_OK) {Serial.printf("INIT_ERROR %s\n",esp_err_to_name(result));while(true)delay(1000);}
  denoiser.reset();humReducer.reset();
#ifdef ECHOCRAFT_NATIVE_USB
  USB.productName("EchoCraft Mic");USB.manufacturerName("EchoCraft");USB.firmwareVersion(0x0102);
  appUsb.enableReboot(false);appUsb.setRxBufferSize(1024);appUsb.setTxTimeoutMs(25);appUsb.begin();USB.begin();
#endif
  Serial.println("EchoCraft realtime-denoise v1.2; output_gain=3.0; hum=125,250Hz/6dB; 32k mono; R=record, B=bypass, D=denoise, T=replay");
}

void loop() {
#ifdef ECHOCRAFT_NATIVE_USB
  while(appUsb.available()) {
    int command=appUsb.read();
    if(command=='S')appStreaming=false;
    if(command=='D')bypass=false;
    if(command=='B')bypass=true;
    if(command=='G') {uartStreaming=false;appSequence=appSampleIndex=0;drainEvents();appOverflowStart=overflows;appStreaming=true;}
  }
  if(!appUsb)appStreaming=false;
#endif
  if(!remaining && Serial.available()) {
    int command=Serial.read();
    if(command=='B') bypass=true;
    if(command=='D') bypass=false;
#ifdef ECHOCRAFT_NATIVE_USB
    if(command=='P') {appStreaming=false;appSequence=appSampleIndex=0;drainEvents();appOverflowStart=overflows;uartStreaming=true;}
    if(command=='Q') uartStreaming=false;
    if(command=='R'||command=='T')uartStreaming=false;
#endif
    if(command=='T') {replay();return;}
    if(command=='R') {
      remaining=320000;capturedMaxUs=0;drainEvents();
      capturedOverflowStart=overflows;capturedErrorStart=readErrors;
      Serial.print("ECPCM1\n");
    }
  }
  size_t bytes=0;
  if(i2s_read(I2S_NUM_0,rawSamples,sizeof(rawSamples),&bytes,pdMS_TO_TICKS(1000))!=ESP_OK || bytes!=sizeof(rawSamples)) {
    ++readErrors;
    if(remaining) {Serial.print("READ_FAILED");remaining=0;}
    return;
  }
  drainEvents();
  for(int i=0;i<256;++i) inputFrame[i]=(rawSamples[i]>>8)/8388608.f;
  processFrame();
#ifdef ECHOCRAFT_NATIVE_USB
  if(appStreaming || uartStreaming) {
    struct __attribute__((packed)) PacketHeader {
      uint32_t magic,sequence,sampleIndex;uint16_t frames,flags;uint32_t checksum;
    } header={0x45434350,appSequence++,appSampleIndex,256,
      uint16_t((bypass?0:1)|(overflows!=appOverflowStart?2:0)),crc32Audio(reinterpret_cast<const uint8_t*>(pcmFrame),512)};
    appSampleIndex+=256;
    uint8_t packet[sizeof(header)+512];memcpy(packet,&header,sizeof(header));memcpy(packet+sizeof(header),pcmFrame,512);
    if(uartStreaming)Serial.write(packet,sizeof(packet));
    else if(appUsb.write(packet,sizeof(packet))!=sizeof(packet))appStreaming=false;
  }
#endif
  if(remaining) {
    uint32_t count=std::min(remaining,uint32_t(256));
    Serial.write(reinterpret_cast<uint8_t*>(pcmFrame),count*2);
    remaining-=count;
    if(!remaining) {
      drainEvents();Serial.print("\nEND\n");
      Serial.printf("ECDSP {\"firmware\":\"realtime-denoise-v1.2\",\"output_gain\":3.0,\"mode\":\"%s\",\"max_dsp_us\":%lu,\"hop_budget_us\":8000,\"overflows\":%lu,\"read_errors\":%lu,\"algorithm_delay_samples\":256}\n",
        bypass?"bypass":"denoise",(unsigned long)capturedMaxUs,
        (unsigned long)(overflows-capturedOverflowStart),(unsigned long)(readErrors-capturedErrorStart));
    }
  }
}
