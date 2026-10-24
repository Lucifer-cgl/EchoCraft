#include <jni.h>
#include <cstdio>
#include <vector>
#include <string>
#include "lame.h"

struct Mp3State {lame_t encoder=nullptr;FILE* file=nullptr;int channels=1;bool failed=false;};
extern "C" JNIEXPORT jlong JNICALL Java_com_echocraft_studio_Mp3Encoder_create(JNIEnv* env,jclass,jbyteArray path,jint rate,jint channels) {
    if(channels<1||channels>2||rate<8000||rate>48000)return 0;
    auto* state=new Mp3State();state->channels=channels;state->encoder=lame_init();
    if(!state->encoder){delete state;return 0;}
    lame_set_in_samplerate(state->encoder,rate);lame_set_out_samplerate(state->encoder,rate);
    lame_set_num_channels(state->encoder,channels);lame_set_mode(state->encoder,channels==1?MONO:JOINT_STEREO);
    lame_set_brate(state->encoder,channels==1?128:192);lame_set_quality(state->encoder,2);
    lame_set_bWriteVbrTag(state->encoder,1);
    if(lame_init_params(state->encoder)<0){lame_close(state->encoder);delete state;return 0;}
    jbyte* name=env->GetByteArrayElements(path,nullptr);
    std::string filename(reinterpret_cast<char*>(name),env->GetArrayLength(path));env->ReleaseByteArrayElements(path,name,JNI_ABORT);
    state->file=fopen(filename.c_str(),"wb+");
    if(!state->file){lame_close(state->encoder);delete state;return 0;}
    return reinterpret_cast<jlong>(state);
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_echocraft_studio_Mp3Encoder_encode(JNIEnv* env,jclass,jlong handle,jshortArray pcm,jint count) {
    auto* state=reinterpret_cast<Mp3State*>(handle);
    if(!state||state->failed||count<0||count>env->GetArrayLength(pcm)||count%state->channels)return false;
    std::vector<unsigned char> output(7200+count*2);
    jshort* samples=env->GetShortArrayElements(pcm,nullptr);
    int bytes=state->channels==1?lame_encode_buffer(state->encoder,samples,samples,count,output.data(),output.size()):
        lame_encode_buffer_interleaved(state->encoder,samples,count/state->channels,output.data(),output.size());
    env->ReleaseShortArrayElements(pcm,samples,JNI_ABORT);
    state->failed=bytes<0||(bytes>0&&fwrite(output.data(),1,bytes,state->file)!=size_t(bytes));
    return !state->failed;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_echocraft_studio_Mp3Encoder_finish(JNIEnv*,jclass,jlong handle) {
    auto* state=reinterpret_cast<Mp3State*>(handle);if(!state)return false;
    unsigned char buffer[7200];int bytes=lame_encode_flush(state->encoder,buffer,sizeof(buffer));
    bool ok=!state->failed&&bytes>=0;
    if(bytes>0&&fwrite(buffer,1,bytes,state->file)!=size_t(bytes))ok=false;
    if(ok)lame_mp3_tags_fid(state->encoder,state->file);
    if(ferror(state->file)||fflush(state->file)!=0)ok=false;
    if(fclose(state->file)!=0)ok=false;
    lame_close(state->encoder);delete state;return ok;
}
