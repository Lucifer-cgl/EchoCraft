package com.echocraft.studio;

final class AudioDsp implements AutoCloseable {
    static { System.loadLibrary("echocraft"); }
    private long state;
    AudioDsp(int channels) { state=create(channels); if(state==0) throw new IllegalArgumentException("不支持的声道数"); }
    void apply(short[] pcm,int count,boolean enabled,float gain) { process(state,pcm,count,enabled,gain); }
    public void close() { if(state!=0) {destroy(state);state=0;} }
    private static native long create(int channels);
    private static native void destroy(long handle);
    private static native void process(long handle,short[] data,int count,boolean enabled,float gain);
}
