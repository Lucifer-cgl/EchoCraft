package com.echocraft.studio;

import java.io.*;

final class Mp3Encoder implements AutoCloseable {
    static {System.loadLibrary("echocraft");}
    long handle;
    Mp3Encoder(File target,int rate,int channels) throws IOException {
        handle=create(target.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8),rate,channels);
        if(handle==0)throw new IOException("MP3 编码器初始化失败");
    }
    void put(short[] samples,int count) throws IOException {
        if(handle==0)throw new IOException("MP3 编码器已关闭");
        if(!encode(handle,samples,count))throw new IOException("MP3 编码或写入失败");
    }
    public void close() throws IOException {
        long current=handle;handle=0;
        if(current!=0&&!finish(current))throw new IOException("MP3 保存失败");
    }
    private static native long create(byte[] path,int rate,int channels);
    private static native boolean encode(long handle,short[] samples,int count);
    private static native boolean finish(long handle);
}
