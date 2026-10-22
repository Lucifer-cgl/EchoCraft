package com.echocraft.studio;

import android.content.Context;
import android.media.*;
import android.os.SystemClock;
import android.view.Surface;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import org.json.JSONObject;

final class CaptureEngine {
    interface Listener {void started(String input);void level(float peak);void finished(File folder,JSONObject metadata,String warning);void failed(String message);}
    final Context context;final CameraController camera;final File folder;final Listener listener;
    final AtomicBoolean stopping=new AtomicBoolean();
    final CountDownLatch initialized=new CountDownLatch(1);
    volatile boolean running=true,denoise;
    volatile String warning="";
    volatile Throwable failure;
    AudioInputs.Input input;MediaCodec video;AacEncoder audio;Mp4Mux mux;Surface surface;
    Thread audioThread,videoThread;
    volatile long epochUs=0;volatile long audioEndUs=0;int startSwitches=0;
    volatile long audioSamples=0;volatile String audioStage="尚未开始";
    CaptureEngine(Context c,CameraController camera,File folder,boolean denoise,Listener l){context=c;this.camera=camera;this.folder=folder;this.denoise=denoise;listener=l;}
    void start(boolean debugInternal) {
        new Thread(()->{
            try {
                input=AudioInputs.create(context,debugInternal);
                MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,camera.size.getWidth(),camera.size.getHeight());
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE,4000000);format.setInteger(MediaFormat.KEY_FRAME_RATE,30);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
                video=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);video.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface=video.createInputSurface();video.start();
                mux=new Mp4Mux(new File(folder,"original.mp4"),2,camera.sensorOrientation);
                audio=new AacEncoder(input.rate(),1,mux);
                videoThread=new Thread(this::drainVideo,"EchoCraft-video");videoThread.start();
                camera.record(surface,()->{
                    if(!running)return;
                    audioThread=new Thread(this::captureAudio,"EchoCraft-audio");audioThread.start();
                });
            }catch(Throwable e){failure=e;stop();}finally{initialized.countDown();}
        },"EchoCraft-start").start();
    }
    void setDenoise(boolean enabled){denoise=enabled;startSwitches++;}
    void captureAudio() {
        try(AudioDsp dsp=new AudioDsp(1)) {
            boolean previous=denoise;audioStage="打开音频输入";input.start(previous);audioStage="等待有效音频";
            boolean notified=false;
            short[] buffer=new short[256];
            while(running) {
                if(previous!=denoise){previous=denoise;input.setDenoise(previous);}
                int count=input.read(buffer);if(count<=0)break;
                if(epochUs==0){epochUs=input.firstSampleUs();android.os.Bundle request=new android.os.Bundle();request.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);video.setParameters(request);}
                if(!notified){notified=true;listener.started(input.name());}audioStage="接收并编码音频";
                long pts=input.firstSampleUs()-epochUs;
                if(pts<0)continue;
                if(!input.boardDsp())dsp.apply(buffer,count,denoise,1);
                float peak=0;for(int i=0;i<count;i++)peak=Math.max(peak,Math.abs((int)buffer[i])/32768f);
                listener.level(peak);
                audio.put(buffer,count,pts);audioSamples+=count;audioEndUs=pts+count*1000000L/input.rate();
            }
        }catch(Throwable e){if(running){warning=e.getMessage();stop();}}
        finally{if(input!=null)input.close();try{audio.finish(audioEndUs);}catch(Throwable e){failure=e;}}
    }
    void drainVideo() {
        try {
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long stoppingSince=0;boolean needsKey=true;
            long encoderOriginUs=-1,cameraOriginUs=0;
            while(true) {
                int index=video.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)mux.format("video",video.getOutputFormat());
                else if(index>=0) {
                    if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0&&encoderOriginUs<0){
                        encoderOriginUs=info.presentationTimeUs;
                        cameraOriginUs=camera.firstRecordFrameUs!=0?camera.firstRecordFrameUs:SystemClock.elapsedRealtimeNanos()/1000-33333;
                    }
                    if(epochUs!=0 && (!needsKey || (info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0)){
                        needsKey=false;info.presentationTimeUs=Math.max(0,cameraOriginUs+info.presentationTimeUs-encoderOriginUs-epochUs);
                        mux.packet("video",video.getOutputBuffer(index),info);
                    }
                    boolean end=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    video.releaseOutputBuffer(index,false);if(end)break;
                }
                if(!running){if(stoppingSince==0)stoppingSince=SystemClock.elapsedRealtime();
                    if(SystemClock.elapsedRealtime()-stoppingSince>7000)throw new IOException("视频编码结束超时");}
            }
        }catch(Throwable e){failure=e;if(running)stop();}
    }
    void stop() {
        if(!stopping.compareAndSet(false,true))return;
        running=false;
        new Thread(()->{
            try {
                if(!initialized.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("录像初始化超时");
                camera.stopRecording();
                if(video!=null)video.signalEndOfInputStream();
                if(audioThread!=null && audioThread!=Thread.currentThread())audioThread.join(6000);
                if(videoThread!=null && videoThread!=Thread.currentThread())videoThread.join(8000);
                if((audioThread!=null&&audioThread.isAlive())||(videoThread!=null&&videoThread.isAlive()))throw new IOException("录像停止超时");
                if(failure!=null)throw new IOException(failure.getMessage(),failure);
                if(mux==null||mux.videoPackets==0||mux.audioPackets==0)throw new IOException(warning.isEmpty()?"没有收到完整音视频":warning);
                mux.close();
                JSONObject metadata=new JSONObject();
                metadata.put("createdAt",System.currentTimeMillis());metadata.put("input",input.name());
                metadata.put("durationMs",audioEndUs/1000);metadata.put("sampleRate",input.rate());
                metadata.put("denoiseAtEnd",denoise);metadata.put("liveSwitches",startSwitches);
                metadata.put("interruption",warning);metadata.put("app","EchoCraft");
                metadata.put("videoFirstUs",mux.first.get("video"));metadata.put("videoLastUs",mux.last.get("video"));
                metadata.put("audioFirstUs",mux.first.get("audio"));metadata.put("audioLastUs",mux.last.get("audio"));
                metadata.put("timestampCorrections",mux.corrections);
                listener.finished(folder,metadata,warning);
            }catch(Throwable e){
                String details=e.getMessage()+"\n音频阶段："+audioStage+"\n音频样本："+audioSamples+
                    "\n视频包："+(mux==null?0:mux.videoPackets)+"，音频包："+(mux==null?0:mux.audioPackets)+
                    (input==null?"":"\n"+input.diagnostics());
                listener.failed(details);new File(folder,"original.mp4").delete();}
            finally {
                try{if(input!=null)input.close();}catch(Exception ignored){}
                try{if(audio!=null)audio.close();}catch(Exception ignored){}
                try{if(video!=null){video.stop();video.release();}}catch(Exception ignored){}
                try{if(mux!=null)mux.close();}catch(Exception ignored){}
                if(surface!=null)surface.release();
            }
        },"EchoCraft-stop").start();
    }
}
