package com.echocraft.studio;

import android.media.*;
import java.nio.*;

final class AacEncoder implements AutoCloseable {
    final MediaCodec codec;
    final Mp4Mux mux;
    final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    boolean ended=false;
    AacEncoder(int rate,int channels,Mp4Mux mux)throws Exception {
        this.mux=mux;
        codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat f=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,channels);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE,channels==1?128000:192000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
        codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
    }
    void put(short[] pcm,int count,long pts)throws Exception {
        int index;
        while((index=codec.dequeueInputBuffer(10000))<0)drain(false);
        ByteBuffer buffer=codec.getInputBuffer(index);buffer.clear();buffer.order(ByteOrder.LITTLE_ENDIAN);
        if(buffer.remaining()<count*2)throw new IllegalArgumentException("编码输入过大");
        buffer.asShortBuffer().put(pcm,0,count);
        codec.queueInputBuffer(index,0,count*2,Math.max(0,pts),0);drain(false);
    }
    void finish(long pts)throws Exception {
        int index;
        while((index=codec.dequeueInputBuffer(10000))<0)drain(false);
        codec.queueInputBuffer(index,0,0,Math.max(0,pts),MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        long deadline=System.currentTimeMillis()+10000;
        while(!ended) {drain(true);if(System.currentTimeMillis()>deadline)throw new IllegalStateException("音频编码结束超时");}
    }
    void drain(boolean wait) {
        while(true) {
            int index=codec.dequeueOutputBuffer(info,wait?10000:0);
            if(index==MediaCodec.INFO_TRY_AGAIN_LATER)return;
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){mux.format("audio",codec.getOutputFormat());continue;}
            if(index<0)continue;
            mux.packet("audio",codec.getOutputBuffer(index),info);
            ended=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
            codec.releaseOutputBuffer(index,false);
            if(ended)return;
        }
    }
    public void close(){try{codec.stop();}catch(Exception ignored){}codec.release();}
}
