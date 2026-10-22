package com.echocraft.studio;

import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.json.JSONObject;

final class VideoEditor {
    interface Progress {void update(String message);}
    record PcmInfo(int rate,int channels,long frames,long firstPtsUs){}
    static int track(MediaExtractor e,String prefix) throws IOException {
        for(int i=0;i<e.getTrackCount();i++)if(e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix))return i;
        throw new IOException("素材没有"+prefix+"轨道");
    }
    static File edit(File source,boolean denoise,int volumePercent,Progress progress)throws Exception {
        if(volumePercent<0||volumePercent>300)throw new IllegalArgumentException("音量范围为 0～300%");
        String id=Long.toString(System.currentTimeMillis())+"-"+UUID.randomUUID().toString().substring(0,4);
        File folder=source.getParentFile(),raw=new File(folder,"pending-"+id+".pcm"),processed=new File(folder,"pending-"+id+"-processed.pcm");
        File audio=new File(folder,"pending-"+id+"-audio.mp4"),pending=new File(folder,"pending-"+id+".mp4"),result=new File(folder,"edit-"+id+".mp4");
        boolean success=false;
        try {
            progress.update("正在解码原音轨…");PcmInfo info=decode(source,raw);
            progress.update("正在逐帧处理声音…");long clipped=process(raw,processed,info,denoise,volumePercent/100f);
            progress.update("正在编码新音轨…");encode(processed,audio,info);
            progress.update("正在合成视频，画面不重新编码…");remux(source,audio,pending);
            MediaMetadataRetriever check=new MediaMetadataRetriever();check.setDataSource(pending.getAbsolutePath());
            String duration=check.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);check.release();
            if(duration==null||Long.parseLong(duration)<=0)throw new IOException("输出视频校验失败");
            if(!pending.renameTo(result))throw new IOException("无法保存处理结果");
            JSONObject report=new JSONObject();report.put("source",source.getName());report.put("denoise",denoise);
            report.put("volumePercent",volumePercent);report.put("rate",info.rate());report.put("channels",info.channels());
            report.put("frames",info.frames());report.put("clippedSamples",clipped);report.put("durationMs",Long.parseLong(duration));
            try(FileOutputStream out=new FileOutputStream(new File(folder,result.getName()+".json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            success=true;return result;
        }finally {
            raw.delete();processed.delete();audio.delete();pending.delete();if(!success)result.delete();
        }
    }
    static PcmInfo decode(File source,File target)throws Exception {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try(FileOutputStream out=new FileOutputStream(target)) {
            extractor.setDataSource(source.getAbsolutePath());int index=track(extractor,"audio/");extractor.selectTrack(index);
            MediaFormat format=extractor.getTrackFormat(index);format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();
            boolean inputEnd=false,outputEnd=false;int rate=0,channels=0;long bytes=0,firstPts=0;boolean first=true;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long idle=System.currentTimeMillis();
            while(!outputEnd) {
                if(!inputEnd) {
                    int i=codec.dequeueInputBuffer(10000);
                    if(i>=0){ByteBuffer b=codec.getInputBuffer(i);b.clear();int n=extractor.readSampleData(b,0);
                        if(n<0){codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true;}
                        else {codec.queueInputBuffer(i,0,n,extractor.getSampleTime(),0);extractor.advance();}}
                }
                int i=codec.dequeueOutputBuffer(info,10000);
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f=codec.getOutputFormat();int r=f.getInteger(MediaFormat.KEY_SAMPLE_RATE),c=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if(c<1||c>2)throw new IOException("当前支持单声道和双声道编辑");
                    if(rate!=0&&(r!=rate||c!=channels))throw new IOException("音频格式在途中改变");
                    if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)&&f.getInteger(MediaFormat.KEY_PCM_ENCODING)!=AudioFormat.ENCODING_PCM_16BIT)throw new IOException("不支持的解码 PCM 格式");
                    rate=r;channels=c;
                }else if(i>=0) {
                    if(info.size>0){ByteBuffer b=codec.getOutputBuffer(i);b.position(info.offset);b.limit(info.offset+info.size);
                        byte[] data=new byte[info.size];b.get(data);out.write(data);bytes+=data.length;
                        if(first){firstPts=Math.max(0,info.presentationTimeUs);first=false;}idle=System.currentTimeMillis();}
                    outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(i,false);
                }
                if(System.currentTimeMillis()-idle>15000)throw new IOException("音频解码超时");
            }
            if(rate==0||channels==0||bytes==0)throw new IOException("解码没有产生有效音频");
            return new PcmInfo(rate,channels,bytes/(2*channels),firstPts);
        }finally{extractor.release();if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}}
    }
    static long process(File source,File target,PcmInfo info,boolean denoise,float gain)throws Exception {
        int channels=info.channels();short[] block=new short[256*channels];byte[] bytes=new byte[block.length*2];long inputFrames=0,written=0,clipped=0;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(source)));
            FileOutputStream out=new FileOutputStream(target);AudioDsp dsp=new AudioDsp(channels)) {
            while(written<info.frames()) {
                Arrays.fill(block,(short)0);
                int count=(int)Math.min(256,Math.max(0,info.frames()-inputFrames));
                for(int i=0;i<count*channels;i++)block[i]=Short.reverseBytes(in.readShort());
                dsp.apply(block,block.length,denoise,gain);
                if(inputFrames>=512) {
                    int n=(int)Math.min(256,info.frames()-written);
                    ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.asShortBuffer().put(block);
                    out.write(bytes,0,n*channels*2);written+=n;
                    for(int i=0;i<n*channels;i++)if(Math.abs((int)block[i])>=32767)clipped++;
                }
                inputFrames+=256;
            }
        }
        return clipped;
    }
    static void encode(File source,File target,PcmInfo info)throws Exception {
        Mp4Mux mux=new Mp4Mux(target,1,0);AacEncoder encoder=null;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(source)))) {
            encoder=new AacEncoder(info.rate(),info.channels(),mux);short[] block=new short[1024*info.channels()];long frames=0;
            while(frames<info.frames()) {
                int n=(int)Math.min(1024,info.frames()-frames);
                for(int i=0;i<n*info.channels();i++)block[i]=Short.reverseBytes(in.readShort());
                encoder.put(block,n*info.channels(),info.firstPtsUs()+frames*1000000/info.rate());frames+=n;
            }
            encoder.finish(info.firstPtsUs()+frames*1000000/info.rate());
        }finally{if(encoder!=null)encoder.close();mux.close();}
    }
    static void remux(File original,File audio,File target)throws Exception {
        MediaExtractor video=new MediaExtractor(),sound=new MediaExtractor();MediaMuxer mux=null;
        try {
            video.setDataSource(original.getAbsolutePath());sound.setDataSource(audio.getAbsolutePath());
            int vi=track(video,"video/"),ai=track(sound,"audio/");video.selectTrack(vi);sound.selectTrack(ai);
            mux=new MediaMuxer(target.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();retriever.setDataSource(original.getAbsolutePath());
            String rotate=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);retriever.release();
            if(rotate!=null)mux.setOrientationHint(Integer.parseInt(rotate));
            int vt=mux.addTrack(video.getTrackFormat(vi)),at=mux.addTrack(sound.getTrackFormat(ai));mux.start();
            ByteBuffer buffer=ByteBuffer.allocateDirect(16*1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(video.getSampleTime()>=0||sound.getSampleTime()>=0) {
                boolean chooseVideo=sound.getSampleTime()<0 || (video.getSampleTime()>=0&&video.getSampleTime()<=sound.getSampleTime());
                MediaExtractor extractor=chooseVideo?video:sound;buffer.clear();int n=extractor.readSampleData(buffer,0);if(n<0)break;
                info.set(0,n,extractor.getSampleTime(),(extractor.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);mux.writeSampleData(chooseVideo?vt:at,buffer,info);extractor.advance();
            }
            mux.stop();
        }finally{video.release();sound.release();if(mux!=null)mux.release();}
    }
}
