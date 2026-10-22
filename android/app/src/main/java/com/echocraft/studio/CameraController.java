package com.echocraft.studio;

import android.content.Context;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.*;
import android.util.Size;
import android.view.*;
import java.util.*;

final class CameraController {
    interface Events {void ready();void error(String message);}
    final Context context;final TextureView preview;final Events events;
    HandlerThread thread;Handler handler;CameraDevice device;CameraCaptureSession session;
    Surface previewSurface;Size size=new Size(1280,720);
    boolean closing=false,recording=false,realtimeClock=false;
    int sensorOrientation=90;
    volatile long clockOffsetUs=0;
    volatile long firstRecordFrameUs=0;
    CameraController(Context c,TextureView p,Events e){context=c;preview=p;events=e;}
    void open() {
        closing=false;
        thread=new HandlerThread("EchoCraft-camera");thread.start();handler=new Handler(thread.getLooper());
        try {
            CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            String selected=null;
            for(String id:manager.getCameraIdList()) {
                CameraCharacteristics ch=manager.getCameraCharacteristics(id);
                if(Objects.equals(ch.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){selected=id;break;}
            }
            if(selected==null)selected=manager.getCameraIdList()[0];
            CameraCharacteristics ch=manager.getCameraCharacteristics(selected);
            sensorOrientation=ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            realtimeClock=Objects.equals(ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
            StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] choices=map.getOutputSizes(android.media.MediaCodec.class);
            if(choices!=null) {
                size=Arrays.stream(choices).filter(s->s.getWidth()<=1920 && s.getHeight()<=1080 && s.getWidth()*9==s.getHeight()*16)
                    .min(Comparator.comparingInt(s->Math.abs(s.getWidth()-1280))).orElse(choices[0]);
            }
            preview.getSurfaceTexture().setDefaultBufferSize(size.getWidth(),size.getHeight());
            previewSurface=new Surface(preview.getSurfaceTexture());
            if(context.checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("请允许相机权限");
            manager.openCamera(selected,new CameraDevice.StateCallback(){
                public void onOpened(CameraDevice camera){if(closing){camera.close();return;}device=camera;configure(null,null);}
                public void onDisconnected(CameraDevice camera){camera.close();device=null;events.error("相机连接断开");}
                public void onError(CameraDevice camera,int error){camera.close();device=null;events.error(error==CameraDevice.StateCallback.ERROR_CAMERA_DISABLED?"系统禁止相机访问，请在系统权限中允许":"相机不可用："+error);}
            },handler);
        }catch(Exception e){events.error(e.getMessage());}
    }
    void record(Surface surface,Runnable ready){recording=true;firstRecordFrameUs=0;handler.post(()->configure(surface,ready));}
    void configure(Surface encoder,Runnable ready) {
        if(device==null||closing)return;
        try {
            if(session!=null){session.close();session=null;}
            List<Surface> targets=new ArrayList<>();targets.add(previewSurface);if(encoder!=null)targets.add(encoder);
            device.createCaptureSession(targets,new CameraCaptureSession.StateCallback(){
                public void onConfigured(CameraCaptureSession s) {
                    if(closing||device==null){s.close();return;}session=s;
                    try {
                        CaptureRequest.Builder request=device.createCaptureRequest(encoder==null?CameraDevice.TEMPLATE_PREVIEW:CameraDevice.TEMPLATE_RECORD);
                        for(Surface target:targets)request.addTarget(target);
                        request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        s.setRepeatingRequest(request.build(),new CameraCaptureSession.CaptureCallback(){
                            public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long timestamp,long frameNumber){
                                if(!realtimeClock)clockOffsetUs=SystemClock.elapsedRealtimeNanos()/1000-timestamp/1000;
                                if(encoder!=null&&firstRecordFrameUs==0){
                                    long now=SystemClock.elapsedRealtimeNanos()/1000,sensor=timestamp/1000;
                                    firstRecordFrameUs=realtimeClock&&Math.abs(sensor-now)<1000000?sensor:now;
                                }
                            }
                        },handler);
                        if(ready!=null)ready.run();else events.ready();
                    }catch(Exception e){events.error(e.getMessage());}
                }
                public void onConfigureFailed(CameraCaptureSession s){events.error("相机预览配置失败");}
            },handler);
        }catch(Exception e){events.error(e.getMessage());}
    }
    long timestampUs(long sensorUs){return sensorUs+(realtimeClock?0:clockOffsetUs);}
    void stopRecording() {
        recording=false;
        try{if(session!=null){session.stopRepeating();session.abortCaptures();session.close();session=null;}}catch(Exception ignored){}
        if(handler!=null)handler.post(()->configure(null,null));
    }
    void close(){closing=true;recording=false;if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}
        if(previewSurface!=null){previewSurface.release();previewSurface=null;}if(thread!=null){thread.quitSafely();thread=null;}}
}
