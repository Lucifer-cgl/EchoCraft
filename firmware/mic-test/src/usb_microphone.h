#pragma once

// USB Audio Class 1 microphone alongside the existing CDC app interface.
// Uses the Arduino core's TinyUSB application class hook, without modifying SDK files.
#include <Arduino.h>
#include "esp32-hal-tinyusb.h"
#include "tusb.h"
#include "device/usbd_pvt.h"
#include "soc/usb_struct.h"
#include "soc/usb_reg.h"

// The bundled ESP32 DCD has no single-endpoint close implementation. Its generic
// TinyUSB close API therefore cannot clear a pending transfer after a host stops
// polling an alternate interface. Supply the missing IN ISO close operation.
extern "C" void dcd_edpt_close(uint8_t rhport,uint8_t address);

namespace UsbMicrophone {
static constexpr size_t capacity=2048;
static int16_t ring[capacity];
static size_t head=0,tail=0,queued=0;
static portMUX_TYPE lock=portMUX_INITIALIZER_UNLOCKED;
static volatile bool active=false;
static bool primed=false;
static uint8_t controlInterface=0xff,streamInterface=0xff,endpoint=0;
static tusb_desc_endpoint_t endpointDescriptor;
static uint8_t alternate=0,frequency[3]={0x00,0x7d,0x00}; // 32000 Hz
static uint8_t packet[66] __attribute__((aligned(4)));
static volatile uint32_t packets=0,underruns=0,overruns=0,transmitErrors=0;

static void clearRing(){portENTER_CRITICAL(&lock);head=tail=queued=0;portEXIT_CRITICAL(&lock);primed=false;}
static void push(const int16_t* samples,size_t count) {
  if(!active)return;
  portENTER_CRITICAL(&lock);
  for(size_t i=0;i<count;++i) {
    if(queued==capacity){++overruns;break;}
    ring[head]=samples[i];head=(head+1)%capacity;++queued;
  }
  portEXIT_CRITICAL(&lock);
}
static void submit(uint8_t rhport) {
  if(!active||!endpoint||usbd_edpt_busy(rhport,endpoint))return;
  size_t count=32;
  portENTER_CRITICAL(&lock);
  if(!primed&&queued>=512)primed=true;
  if(primed) {
    // Asynchronous microphone: vary packet length slightly to follow its sampling clock.
    if(queued>1536)count=33;else if(queued<256)count=31;
  }
  for(size_t i=0;i<count;++i) {
    int16_t sample=0;
    if(primed&&queued){sample=ring[tail];tail=(tail+1)%capacity;--queued;}
    else if(primed)++underruns;
    packet[2*i]=uint8_t(sample);packet[2*i+1]=uint8_t(uint16_t(sample)>>8);
  }
  portEXIT_CRITICAL(&lock);
  // The legacy ESP32 DCD does not select ISO frame parity. Select the NEXT
  // frame explicitly; leaving the default parity sends only every second ms.
  uint32_t frame=(USB0.dsts&USB_SOFFN_M)>>USB_SOFFN_S;
  auto& ctl=USB0.in_ep_reg[endpoint&0x0f].diepctl;
  ctl=(ctl&~(USB_DI_SETD0PID1_M|USB_DI_SETD1PID1_M))|
      ((frame&1)?USB_DI_SETD0PID1_M:USB_DI_SETD1PID1_M);
  if(!usbd_edpt_xfer(rhport,endpoint,packet,count*2))++transmitErrors;
}
static uint16_t descriptor(uint8_t* dst,uint8_t* interface) {
  controlInterface=*interface;streamInterface=*interface+1;*interface+=2;
  endpoint=0x80|tinyusb_get_free_in_endpoint();
  if(endpoint==0x80)return 0;
  const uint8_t bytes[]={
    8,0x0b,controlInterface,2,1,1,0,0, // UAC1 IAD matches AudioControl subclass
    9,4,controlInterface,0,0,1,1,0,0, // AudioControl
    9,0x24,1,0,1,30,0,1,streamInterface, // UAC1 AC header
    12,0x24,2,1,1,2,0,1,0,0,0,0, // physical mono microphone terminal
    9,0x24,3,2,1,1,0,1,0, // USB streaming output terminal, source=1
    9,4,streamInterface,0,0,1,2,0,0, // AudioStreaming alternate zero
    9,4,streamInterface,1,1,1,2,0,0, // AudioStreaming alternate one
    7,0x24,1,2,1,1,0, // link terminal 2, PCM
    11,0x24,2,1,1,2,16,1,0,0x7d,0, // mono signed 16-bit, 32000 Hz
    9,5,endpoint,5,66,0,1,0,0, // async isochronous input, max 33 samples/ms
    7,0x25,1,1,0,0,0 // sample-frequency control
  };
  static_assert(sizeof(bytes)==99,"Invalid USB audio descriptor length");
  memcpy(dst,bytes,sizeof(bytes));return sizeof(bytes);
}
static void init(){}
static void reset(uint8_t){active=false;alternate=0;clearRing();}
static uint16_t open(uint8_t rhport,const tusb_desc_interface_t* itf,uint16_t maxLength) {
  if(itf->bInterfaceClass!=1)return 0;
  if(itf->bInterfaceNumber!=controlInterface)return 0;
  const uint8_t* bytes=reinterpret_cast<const uint8_t*>(itf);uint16_t length=itf->bLength;
  while(length+2<=maxLength) {
    uint8_t size=bytes[length],type=bytes[length+1];
    if(size<2||length+size>maxLength)break;
    // An IAD collection must be claimed in one open() call, including all AS alternates.
    if(type==4&&bytes[length+2]!=controlInterface&&bytes[length+2]!=streamInterface)break;
    if(type==5&&size>=7)memcpy(&endpointDescriptor,bytes+length,sizeof(endpointDescriptor));
    length+=size;
  }
  // Use the endpoint-opening path supported by this legacy ESP32 DCD.
  // Open once per configuration, keeping FIFO allocation stable across alternate settings.
  if(!usbd_edpt_open(rhport,&endpointDescriptor))return 0;
  return length;
}
static bool control(uint8_t rhport,uint8_t stage,const tusb_control_request_t* request) {
  if(stage!=CONTROL_STAGE_SETUP)return true;
  if(request->bmRequestType_bit.type==TUSB_REQ_TYPE_STANDARD) {
    if(request->wIndex!=streamInterface)return false;
    if(request->bRequest==TUSB_REQ_GET_INTERFACE)return tud_control_xfer(rhport,request,&alternate,1);
    if(request->bRequest==TUSB_REQ_SET_INTERFACE&&request->wValue<=1) {
      active=false;
      usbd_edpt_close(rhport,endpoint);
      alternate=request->wValue;clearRing();
      if(alternate) {
        packets=underruns=overruns=transmitErrors=0;active=true;submit(rhport);
      }
      return tud_control_status(rhport,request);
    }
  }
  if(request->bmRequestType_bit.type==TUSB_REQ_TYPE_CLASS&&request->bmRequestType_bit.recipient==TUSB_REQ_RCPT_ENDPOINT&&uint8_t(request->wIndex)==endpoint&&request->wValue==0x0100&&request->wLength==3) {
    if(request->bRequest==0x01)return tud_control_xfer(rhport,request,frequency,3);
    if(request->bRequest==0x81||request->bRequest==0x82||request->bRequest==0x83) {
      frequency[0]=0;frequency[1]=0x7d;frequency[2]=0;return tud_control_xfer(rhport,request,frequency,3);
    }
    if(request->bRequest==0x84){static uint8_t zero[3]={};return tud_control_xfer(rhport,request,zero,3);}
  }
  return false;
}
static bool transfer(uint8_t rhport,uint8_t ep,xfer_result_t result,uint32_t) {
  if(ep!=endpoint)return false;
  if(result!=XFER_RESULT_SUCCESS)++transmitErrors;
  ++packets;submit(rhport);return true;
}
static void sof(uint8_t rhport,uint32_t){submit(rhport);}
static bool begin(){return tinyusb_enable_interface(USB_INTERFACE_CUSTOM,99,descriptor)==ESP_OK;}
static void diagnostics(){
  Serial.printf("ECUAC active=%u packets=%lu underruns=%lu overruns=%lu tx_errors=%lu\n",unsigned(active),(unsigned long)packets,(unsigned long)underruns,(unsigned long)overruns,(unsigned long)transmitErrors);
  const uint8_t* device=tud_descriptor_device_cb();Serial.print("USB_DEVICE ");for(int i=0;i<18;++i)Serial.printf("%02x",device[i]);Serial.println();
  const uint8_t* config=tud_descriptor_configuration_cb(0);size_t size=config[2]|(config[3]<<8);Serial.print("USB_CONFIG ");for(size_t i=0;i<size;++i)Serial.printf("%02x",config[i]);Serial.println();
}
}

extern "C" void dcd_edpt_close(uint8_t,uint8_t address) {
  if(address!=UsbMicrophone::endpoint)return;
  const uint8_t number=address&0x0f;
  auto& ep=USB0.in_ep_reg[number];
  USB0.dtknqr4_fifoemptymsk&=~(1UL<<number);
  if(ep.diepctl&USB_D_EPENA1_M) {
    ep.diepctl|=USB_DI_SNAK1_M|USB_D_EPDIS1_M;
    const uint32_t start=micros();
    while((ep.diepctl&USB_D_EPENA1_M)&&micros()-start<2000){}
  }
  const uint32_t fifo=(ep.diepctl&USB_D_TXFNUM1_M)>>USB_D_TXFNUM1_S;
  USB0.grstctl=(USB0.grstctl&~USB_TXFNUM_M)|(fifo<<USB_TXFNUM_S)|USB_TXFFLSH_M;
  const uint32_t start=micros();
  while((USB0.grstctl&USB_TXFFLSH_M)&&micros()-start<2000){}
  ep.dieptsiz=0;ep.diepint=0xffffffff;
}

extern "C" const usbd_class_driver_t* usbd_app_driver_get_cb(uint8_t* count) {
  static const usbd_class_driver_t driver={
#if CFG_TUSB_DEBUG >= CFG_TUD_LOG_LEVEL
    .name="EchoCraft UAC1",
#endif
    .init=UsbMicrophone::init,.reset=UsbMicrophone::reset,.open=UsbMicrophone::open,
    .control_xfer_cb=UsbMicrophone::control,.xfer_cb=UsbMicrophone::transfer,.sof=UsbMicrophone::sof
  };
  *count=1;return &driver;
}

// The core's default string callback widens bytes, so override it for a UTF-16 name.
extern "C" const uint16_t* tud_descriptor_string_cb(uint8_t index,uint16_t) {
  static uint16_t descriptor[64];
  if(index==0){descriptor[0]=0x0304;descriptor[1]=0x0409;return descriptor;}
  const char16_t* text=index==1?u"EchoCraft":index==2?u"EchoCraft 智能收音系统":index==3?u"EchoCraft-UAC-Prototype":u"EchoCraft Audio";
  size_t length=0;while(text[length]&&length<63){descriptor[length+1]=text[length];++length;}
  descriptor[0]=uint16_t(0x0300|(2*length+2));return descriptor;
}
