package com.echocraft.studio;

import android.content.Context;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import org.json.JSONObject;

/** Debug-only acceptance checks, confined to this test's cache directory. */
final class DebugChecks {
    static JSONObject run(Context context,File testFolder,File sampleVideo)throws Exception {
        JSONObject report=new JSONObject();
        File base=new File(testFolder,"checks");base.mkdirs();
        int frames=32000,channels=2;
        short[] original=new short[frames*channels];
        ByteBuffer bytes=ByteBuffer.allocate(original.length*2).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<frames;i++){
            original[2*i]=(short)Math.round(5000*Math.sin(2*Math.PI*440*i/32000));
            original[2*i+1]=(short)Math.round(3500*Math.sin(2*Math.PI*997*i/32000));
        }
        bytes.asShortBuffer().put(original);
        File source=new File(base,"reference.pcm"),half=new File(base,"half.pcm"),mute=new File(base,"mute.pcm");
        Files.write(source.toPath(),bytes.array());
        VideoEditor.PcmInfo info=new VideoEditor.PcmInfo(32000,channels,frames,0);
        VideoEditor.process(source,half,info,false,.5f);
        byte[] result=Files.readAllBytes(half.toPath());if(result.length!=original.length*2)throw new IOException("PCM frame count changed");
        ShortBuffer decoded=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();int maximum=0;
        for(short input:original)maximum=Math.max(maximum,Math.abs(decoded.get()-Math.round(input*.5f)));
        if(maximum>1)throw new IOException("Bypass latency/gain reconstruction failed");
        VideoEditor.process(source,mute,info,true,0);
        for(byte b:Files.readAllBytes(mute.toPath()))if(b!=0)throw new IOException("Mute did not produce silence");
        report.put("pcmFramesPreserved",true);report.put("bypassGainMaxErrorLsb",maximum);report.put("mutePassed",true);
        ClipStore store=new ClipStore(new File(base,"library"));
        File session=store.newSession();Files.copy(sampleVideo.toPath(),new File(session,"original.mp4").toPath());
        Files.copy(sampleVideo.toPath(),new File(session,"edit-test.mp4").toPath());store.commit(session,new JSONObject().put("input","test"));
        File orphan=new File(store.root,"unrelated.mp4");Files.write(orphan.toPath(),new byte[]{1});
        File uncommitted=new File(store.root,"pending-session");uncommitted.mkdir();Files.copy(sampleVideo.toPath(),new File(uncommitted,"original.mp4").toPath());
        if(store.sessions().size()!=1||store.versions(session).size()!=2)throw new IOException("Library indexing failed");
        store.delete(session);if(session.exists()||!store.sessions().isEmpty())throw new IOException("Session deletion failed");
        File outside=new File(base,"outside.txt");Files.write(outside.toPath(),new byte[]{1});boolean blocked=false;
        try{store.delete(outside);}catch(IOException expected){blocked=true;}
        if(!blocked||!outside.exists())throw new IOException("Deletion boundary failed");
        store.delete(orphan);store.delete(uncommitted);
        report.put("libraryExcludesUnownedAndIncomplete",true);report.put("deleteRemovesVideoAndVersions",true);report.put("deleteOutsideLibraryBlocked",true);
        return report;
    }
}
