package com.echocraft.studio;

import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

final class Mp4Mux implements AutoCloseable {
    final MediaMuxer muxer;
    final int expected;
    final Map<String,Integer> tracks=new HashMap<>();
    final Map<String,Long> last=new HashMap<>();
    final Map<String,Long> first=new HashMap<>();
    int corrections=0,videoCorrections=0;
    final List<Packet> pending=new ArrayList<>();
    boolean started=false,closed=false;
    int videoPackets=0,audioPackets=0,queuedBytes=0;
    record Packet(String kind,byte[] bytes,long pts,int flags){}
    Mp4Mux(File file,int expected,int rotation)throws IOException {
        this.expected=expected;muxer=new MediaMuxer(file.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(rotation);
    }
    synchronized void format(String kind,MediaFormat f) {
        if(tracks.containsKey(kind))throw new IllegalStateException("编码格式意外改变");
        tracks.put(kind,muxer.addTrack(f));
        if(tracks.size()==expected) {
            muxer.start();started=true;
            pending.sort(Comparator.comparingLong(Packet::pts));
            for(Packet p:pending)write(p);
            pending.clear();queuedBytes=0;
        }
    }
    synchronized void packet(String kind,ByteBuffer data,MediaCodec.BufferInfo info) {
        if(closed||info.size<=0||(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)return;
        ByteBuffer b=data.duplicate();b.position(info.offset);b.limit(info.offset+info.size);
        byte[] copy=new byte[info.size];b.get(copy);
        Packet p=new Packet(kind,copy,Math.max(0,info.presentationTimeUs),info.flags&~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        if(started)write(p);else {
            pending.add(p);queuedBytes+=copy.length;
            if(queuedBytes>8*1024*1024)throw new IllegalStateException("音视频轨道启动超时");
        }
    }
    private void write(Packet p) {
        if(p.pts()<=last.getOrDefault(p.kind(),-1L)){
            corrections++;
            if(p.kind().equals("video")&&++videoCorrections>3)throw new IllegalStateException("相机视频时间戳异常，已停止保存");
        }
        long pts=Math.max(p.pts(),last.getOrDefault(p.kind(),-1L)+1);
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,p.bytes().length,pts,p.flags());
        muxer.writeSampleData(tracks.get(p.kind()),ByteBuffer.wrap(p.bytes()),info);last.put(p.kind(),pts);
        first.putIfAbsent(p.kind(),pts);
        if(p.kind().equals("video"))videoPackets++;else audioPackets++;
    }
    public synchronized void close() {
        if(closed)return;closed=true;
        try {if(started)muxer.stop();}finally{muxer.release();}
    }
}
