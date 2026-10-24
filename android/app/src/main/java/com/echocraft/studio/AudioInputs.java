package com.echocraft.studio;

import android.content.Context;
import android.hardware.usb.*;
import android.media.*;
import android.media.audiofx.*;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.*;
import java.util.*;
import java.util.zip.CRC32;
import java.net.*;
import java.io.*;

final class AudioInputs {
    interface Input extends AutoCloseable {
        int rate();
        String name();
        boolean boardDsp();
        void start(boolean denoise) throws Exception;
        int read(short[] buffer) throws Exception;
        long firstSampleUs();
        void setDenoise(boolean enabled) throws Exception;
        default String diagnostics(){return name();}
        void close();
    }
    static UsbDevice echoDevice(Context c) {
        UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);
        for(UsbDevice d:manager.getDeviceList().values()) {
            String product=d.getProductName();
            if(d.getVendorId()==0x303a && product!=null && product.startsWith("EchoCraft")) return d;
        }
        return null;
    }
    static AudioDeviceInfo usbAudio(Context c) {
        AudioManager manager=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE || d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET || d.getType()==AudioDeviceInfo.TYPE_USB_ACCESSORY) return d;
        return null;
    }
    static Input create(Context c,boolean debugInternal) throws Exception {
        boolean bridge=(c.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
            &&c.getSharedPreferences("debug",0).getBoolean("computerBridge",false)&&!debugInternal;
        if(bridge)return new BridgeInput();
        UsbDevice echo=echoDevice(c);
        if(echo!=null) return new EchoInput(c,echo);
        AudioDeviceInfo usb=usbAudio(c);
        if(usb!=null) return new AndroidInput(c,usb);
        if(debugInternal) return new AndroidInput(c,null);
        throw new IOException("未找到可用的外置音频输入。请将手机连接开发板的 USB 接口，并使用 App USB 固件。");
    }

    static final class BridgeInput implements Input {
        Socket socket;InputStream input;OutputStream output;
        long packets=0,bytes=0,expected=-1,first=-1,origin,timestamp;
        public int rate(){return 32000;}
        public String name(){return "EchoCraft Mic · 电脑中转调试";}
        public boolean boardDsp(){return true;}
        public void start(boolean denoise)throws Exception {
            socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",28732),3000);socket.setSoTimeout(3000);socket.setTcpNoDelay(true);
            input=socket.getInputStream();output=socket.getOutputStream();
            ByteArrayOutputStream hello=new ByteArrayOutputStream();int b;
            while((b=input.read())!=-1 && b!='\n'){hello.write(b);if(hello.size()>64)throw new IOException("中转协议错误");}
            if(!hello.toString("UTF-8").equals("ECHOBRIDGE1"))throw new IOException("电脑中转服务未就绪");
            setDenoise(denoise);output.write('G');output.flush();
        }
        public synchronized void setDenoise(boolean enabled)throws Exception{if(output!=null){output.write(enabled?'D':'B');output.flush();}}
        byte[] exact(int n)throws Exception{byte[] b=new byte[n];int p=0;while(p<n){int k=input.read(b,p,n-p);if(k<0)throw new IOException("电脑音频中转已断开");p+=k;bytes+=k;}return b;}
        public int read(short[] pcm)throws Exception {
            ByteBuffer h=ByteBuffer.wrap(exact(20)).order(ByteOrder.LITTLE_ENDIAN);
            if(h.getInt()!=0x45434350)throw new IOException("中转音频包头错误");
            long seq=Integer.toUnsignedLong(h.getInt()),index=Integer.toUnsignedLong(h.getInt());int frames=Short.toUnsignedInt(h.getShort()),flags=Short.toUnsignedInt(h.getShort());long crc=Integer.toUnsignedLong(h.getInt());
            if(frames!=256||pcm.length<frames)throw new IOException("中转音频长度错误");
            if(expected!=-1&&seq!=expected)throw new IOException("中转音频丢包");expected=(seq+1)&0xffffffffL;
            if((flags&2)!=0)throw new IOException("开发板采样溢出");
            byte[] payload=exact(frames*2);CRC32 check=new CRC32();check.update(payload);if(check.getValue()!=crc)throw new IOException("中转音频校验失败");
            if(first<0){first=index;origin=SystemClock.elapsedRealtimeNanos()/1000-24000;}
            timestamp=origin+((index-first)&0xffffffffL)*1000000/rate();
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm,0,frames);packets++;return frames;
        }
        public long firstSampleUs(){return timestamp;}
        public String diagnostics(){return "电脑中转有效包："+packets+"\n字节："+bytes;}
        public void close(){try{if(output!=null){output.write('S');output.flush();}}catch(Exception ignored){}try{if(socket!=null)socket.close();}catch(Exception ignored){}}
    }

    static final class AndroidInput implements Input {
        final Context context;
        final AudioDeviceInfo preferred;
        AudioRecord recorder;
        final List<android.media.audiofx.AudioEffect> effects=new ArrayList<>();
        long frames=0,timestamp;
        volatile boolean closed=false;
        AndroidInput(Context c,AudioDeviceInfo d) {context=c;preferred=d;}
        public int rate(){return 32000;}
        public boolean boardDsp(){return false;}
        public String name(){return preferred==null?"手机麦克风 · 开发测试":preferred.getProductName().toString();}
        public void start(boolean denoise) throws Exception {
            AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            int source="true".equals(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))
                ?MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.VOICE_RECOGNITION;
            int minimum=AudioRecord.getMinBufferSize(rate(),AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IOException("设备不支持所需录音格式");
            if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("请允许录音权限");
            recorder=new AudioRecord.Builder().setAudioSource(source).setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(rate()).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum*4,8192)).build();
            if(preferred!=null&&!recorder.setPreferredDevice(preferred))throw new IOException("无法选择 USB 麦克风");
            int id=recorder.getAudioSessionId();
            if(AutomaticGainControl.isAvailable())effects.add(AutomaticGainControl.create(id));
            if(NoiseSuppressor.isAvailable())effects.add(NoiseSuppressor.create(id));
            if(AcousticEchoCanceler.isAvailable())effects.add(AcousticEchoCanceler.create(id));
            for(android.media.audiofx.AudioEffect e:effects)if(e!=null)e.setEnabled(false);
            recorder.startRecording();
            long deadline=SystemClock.elapsedRealtime()+1000;
            while(preferred!=null && (recorder.getRoutedDevice()==null || recorder.getRoutedDevice().getId()!=preferred.getId())) {
                if(SystemClock.elapsedRealtime()>deadline)throw new IOException("系统没有切到指定 USB 麦克风，已取消录音");
                Thread.sleep(20);
            }
        }
        public int read(short[] buffer) throws Exception {
            if(closed)return -1;
            if(preferred!=null && (recorder.getRoutedDevice()==null||recorder.getRoutedDevice().getId()!=preferred.getId()))
                throw new IOException("外置音频断开或输入发生变化，已停止录像");
            int n=recorder.read(buffer,0,Math.min(buffer.length,256),AudioRecord.READ_BLOCKING);
            if(n<=0) {if(closed)return -1;throw new IOException("音频读取失败："+n);}
            AudioTimestamp ts=new AudioTimestamp();
            if(recorder.getTimestamp(ts,AudioTimestamp.TIMEBASE_BOOTTIME)==AudioRecord.SUCCESS)
                timestamp=ts.nanoTime/1000+(frames-ts.framePosition)*1000000/rate();
            else timestamp=SystemClock.elapsedRealtimeNanos()/1000-n*1000000L/rate();
            frames+=n;return n;
        }
        public long firstSampleUs(){return timestamp;}
        public void setDenoise(boolean enabled){}
        public void close(){closed=true;if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;}
            for(android.media.audiofx.AudioEffect e:effects)if(e!=null)e.release();effects.clear();}
    }

    static final class EchoInput implements Input {
        final UsbManager manager;final UsbDevice device;
        UsbDeviceConnection connection;UsbInterface control,data;
        UsbEndpoint in,out;
        final byte[] cache=new byte[8192];int pos=0,limit=0;
        long expected=-1,firstIndex=-1,originUs,timestamp;
        long receivedBytes=0,packets=0;int lastFlags=0;String stage="等待打开设备";
        volatile boolean closed=false;
        EchoInput(Context c,UsbDevice d){manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);device=d;}
        public int rate(){return 32000;}
        public String name(){return "EchoCraft Mic · USB 直连";}
        public boolean boardDsp(){return true;}
        public void start(boolean denoise)throws Exception {
            stage="检查 USB 权限";
            if(!manager.hasPermission(device))throw new IOException("请先点击设备卡片，授权访问 EchoCraft");
            stage="打开 USB 设备";connection=manager.openDevice(device);if(connection==null)throw new IOException("无法打开 USB 设备");
            for(int i=0;i<device.getInterfaceCount();i++) {
                UsbInterface iface=device.getInterface(i);
                if(iface.getInterfaceClass()==UsbConstants.USB_CLASS_COMM)control=iface;
                if(iface.getInterfaceClass()==UsbConstants.USB_CLASS_CDC_DATA)data=iface;
            }
            if(control==null||data==null)throw new IOException("固件不提供 EchoCraft USB 音频通路");
            if(!connection.claimInterface(control,true)||!connection.claimInterface(data,true))throw new IOException("USB 接口被占用");
            for(int i=0;i<data.getEndpointCount();i++) {
                UsbEndpoint e=data.getEndpoint(i);
                if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if(e.getDirection()==UsbConstants.USB_DIR_IN)in=e;else out=e;
                }
            }
            if(in==null||out==null)throw new IOException("USB 传输端点缺失");
            byte[] coding=ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN).putInt(921600).put((byte)0).put((byte)0).put((byte)8).array();
            stage="配置 USB CDC";
            if(connection.controlTransfer(0x21,0x20,0,control.getId(),coding,7,1000)<0)throw new IOException("USB 音频初始化失败");
            if(connection.controlTransfer(0x21,0x22,3,control.getId(),null,0,1000)<0)throw new IOException("USB 控制线配置失败");
            Thread.sleep(120);send('S');
            // A previous interrupted attempt may leave queued packets; do not treat them as a new stream.
            for(int i=0;i<16;i++)if(connection.bulkTransfer(in,cache,cache.length,15)<=0)break;
            pos=limit=0;expected=firstIndex=-1;receivedBytes=packets=0;
            setDenoise(denoise);send('G');stage="等待第一个音频包";
        }
        synchronized void send(char c)throws IOException {
            if(connection==null||closed)throw new IOException("USB 已关闭");
            if(connection.bulkTransfer(out,new byte[]{(byte)c},1,1000)!=1)throw new IOException("USB 控制命令发送失败");
        }
        public void setDenoise(boolean enabled)throws Exception{send(enabled?'D':'B');}
        int nextByte()throws IOException {
            if(pos>=limit) {
                pos=0;limit=connection.bulkTransfer(in,cache,cache.length,1500);
                if(limit<=0)throw new IOException("USB 音频读取超时（返回 "+limit+"，已收到 "+receivedBytes+" 字节 / "+packets+" 包）");
                receivedBytes+=limit;
            }
            return cache[pos++]&255;
        }
        byte[] bytes(int count)throws IOException{byte[] result=new byte[count];for(int i=0;i<count;i++)result[i]=(byte)nextByte();return result;}
        public int read(short[] buffer)throws Exception {
            if(closed)return -1;
            int magic=0,scanned=0;
            while(magic!=0x45434350) {magic=(magic>>>8)|(nextByte()<<24);if(++scanned>65536)throw new IOException("不是有效的 EchoCraft 音频数据");}
            ByteBuffer header=ByteBuffer.wrap(bytes(16)).order(ByteOrder.LITTLE_ENDIAN);
            long sequence=Integer.toUnsignedLong(header.getInt()),sampleIndex=Integer.toUnsignedLong(header.getInt());
            int frames=Short.toUnsignedInt(header.getShort()),flags=Short.toUnsignedInt(header.getShort());
            lastFlags=flags;stage="校验音频包 "+sequence;
            long checksum=Integer.toUnsignedLong(header.getInt());
            if(frames!=256||buffer.length<frames)throw new IOException("不支持的音频包长度");
            if(expected!=-1&&sequence!=expected)throw new IOException("USB 音频包丢失，已停止录像");
            expected=(sequence+1)&0xffffffffL;
            byte[] payload=bytes(frames*2);CRC32 crc=new CRC32();crc.update(payload);
            if(crc.getValue()!=checksum)throw new IOException("USB 音频校验失败");
            if((flags&2)!=0)throw new IOException("设备音频缓冲溢出，已停止录像");
            if(firstIndex==-1){firstIndex=sampleIndex;originUs=SystemClock.elapsedRealtimeNanos()/1000-16000;}
            timestamp=originUs+((sampleIndex-firstIndex)&0xffffffffL)*1000000/rate();
            ByteBuffer pcm=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            pcm.asShortBuffer().get(buffer,0,frames);packets++;stage="正在接收音频";return frames;
        }
        public long firstSampleUs(){return timestamp;}
        public String diagnostics(){return "阶段："+stage+"\nUSB 字节："+receivedBytes+"\n有效包："+packets+"\n上次标志："+lastFlags;}
        public synchronized void close(){if(closed)return;closed=true;
            UsbDeviceConnection active=connection;connection=null;
            if(active!=null){
                try{if(out!=null)active.bulkTransfer(out,new byte[]{'S'},1,300);}catch(Exception ignored){}
                try{if(in!=null)for(int i=0;i<16;i++)if(active.bulkTransfer(in,cache,cache.length,10)<=0)break;}catch(Exception ignored){}
                try{if(control!=null)active.controlTransfer(0x21,0x22,0,control.getId(),null,0,300);}catch(Exception ignored){}
                try{if(data!=null)active.releaseInterface(data);}catch(Exception ignored){}
                try{if(control!=null)active.releaseInterface(control);}catch(Exception ignored){}
                active.close();
            }
        }
    }
}
