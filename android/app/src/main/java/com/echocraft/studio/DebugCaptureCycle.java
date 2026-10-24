package com.echocraft.studio;
import android.content.Context;
import java.io.*;
import java.util.concurrent.*;
import org.json.JSONObject;
/** Explicit diagnostic: same process and connection, with deletion between captures. */
final class DebugCaptureCycle {
    static JSONObject run(Context c) throws Exception {
        ClipStore store=new ClipStore(c);JSONObject result=new JSONObject();
        for(int i=0;i<3;i++) {
            File folder=store.newSession();CountDownLatch done=new CountDownLatch(1);String[] error={null};
            CaptureEngine[] engine=new CaptureEngine[1];
            try {
                engine[0]=new CaptureEngine(c,null,folder,true,true,new CaptureEngine.Listener(){
                    public void started(String source){new Thread(()->{try{Thread.sleep(1200);}catch(InterruptedException e){Thread.currentThread().interrupt();}engine[0].stop();}).start();}
                    public void level(float peak){}
                    public void finished(File f,JSONObject meta,String warning){try{store.commit(f,meta);if(!warning.isEmpty())error[0]=warning;}catch(Exception e){error[0]=e.toString();}done.countDown();}
                    public void failed(String message){error[0]=message;done.countDown();}
                });
                engine[0].start(false);
                if(!done.await(20,TimeUnit.SECONDS))throw new IOException("Repeat capture timeout");
                if(error[0]!=null)throw new IOException(error[0]);
                if(new File(folder,"original.mp3").length()<1000)throw new IOException("Empty repeat capture");
                PublicVideos.publish(c,new File(folder,"original.mp3"));store.delete(folder);
                if(folder.exists())throw new IOException("Deleted session remains");
                result.put("recordDeleteRecordCycle"+(i+1),true);
            }finally{if(engine[0]!=null)engine[0].stop();if(folder.exists())store.delete(folder);}
        }
        return result;
    }
}
