#include <jni.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include "realtime_denoiser.h"

struct State {
    int channels, cursor=0;
    std::vector<RealtimeDenoiser> dsp;
    std::vector<float> input, output;
    explicit State(int c):channels(c),dsp(c),input(c*256),output(c*256) {
        for(auto& d:dsp) d.reset();
    }
};
extern "C" JNIEXPORT jlong JNICALL Java_com_echocraft_studio_AudioDsp_create(JNIEnv*,jclass,jint channels) {
    if(channels<1 || channels>2) return 0;
    return reinterpret_cast<jlong>(new State(channels));
}
extern "C" JNIEXPORT void JNICALL Java_com_echocraft_studio_AudioDsp_destroy(JNIEnv*,jclass,jlong handle) {
    delete reinterpret_cast<State*>(handle);
}
extern "C" JNIEXPORT void JNICALL Java_com_echocraft_studio_AudioDsp_process(JNIEnv* env,jclass,jlong handle,jshortArray samples,jint count,jboolean enabled,jfloat gain) {
    auto* s=reinterpret_cast<State*>(handle);
    if(!s || count<0 || count>env->GetArrayLength(samples) || count%s->channels) return;
    jshort* data=env->GetShortArrayElements(samples,nullptr);
    for(int i=0;i<count;i+=s->channels) {
        for(int c=0;c<s->channels;++c) {
            s->input[c*256+s->cursor]=data[i+c]/32768.f;
            float v=s->output[c*256+s->cursor]*gain*32768;
            data[i+c]=static_cast<short>(std::max(-32768.f,std::min(32767.f,std::round(v))));
        }
        if(++s->cursor==256) {
            for(int c=0;c<s->channels;++c) s->dsp[c].process(&s->input[c*256],&s->output[c*256],!enabled);
            s->cursor=0;
        }
    }
    env->ReleaseShortArrayElements(samples,data,0);
}
