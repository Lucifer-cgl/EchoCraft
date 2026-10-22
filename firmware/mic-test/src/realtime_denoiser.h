#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>

// Causal sqrt-Hann STFT, 512 samples / 256 hop at 32 kHz.
// No source classification; noise estimate and short-excess attenuation are bounded.
class RealtimeDenoiser {
 public:
  static constexpr int N = 512, H = 256, K = N/2+1;
  void reset() {
    std::memset(previous, 0, sizeof(previous));
    std::memset(tail, 0, sizeof(tail));
    std::memset(noise, 0, sizeof(noise));
    std::memset(history, 0, sizeof(history));
    for (int i=0;i<N;++i) window[i]=std::sqrt(0.5f-0.5f*std::cos(2*kPi*i/N));
    for (int i=0;i<K;++i) smooth[i]=1;
    frames=0;
  }
  void process(const float* input, float* output, bool bypass=false) {
    for(int i=0;i<H;++i) {
      real[i]=previous[i]*window[i]; real[i+H]=input[i]*window[i+H];
      previous[i]=input[i];
    }
    std::memset(imag,0,sizeof(imag));
    fft(false);
    for(int k=0;k<K;++k) power[k]=real[k]*real[k]+imag[k]*imag[k];
    // Cache frequency-local statistics: avoid thousands of repeated software divides per hop.
    powerSum[0]=excessSum[0]=0;
    for(int k=0;k<K;++k) {
      excess[k]=frames ? clip((power[k]/(history[k]+1e-20f)-6.f)/20.f) : 0;
      powerSum[k+1]=powerSum[k]+power[k];
      excessSum[k+1]=excessSum[k]+excess[k];
    }
    for(int k=0;k<K;++k) {
      float p=power[k];
      if(frames==0) noise[k]=history[k]=p;
      // Slow upwards / faster downwards tracking: avoid absorbing loud target onsets.
      float alpha=p<noise[k] ? .12f : (p<noise[k]*2.5f ? .002f : .00002f);
      if(frames>0) noise[k]+=alpha*(p-noise[k]);
      int left=std::max(0,k-5),right=std::min(K-1,k+5);
      float local=(powerSum[right+1]-powerSum[left])/(right-left+1);
      float tonal=clip((p/(local+1e-20f)-1.8f)/3.0f);
      float stationary=std::sqrt(std::max(0.f,1-.7f*noise[k]/(p+1e-20f)));
      stationary=std::max(.501187f,stationary); // 6 dB maximum stationary attenuation
      stationary+=.85f*tonal*(1-stationary);
      float burst=excess[k];
      left=std::max(0,k-8);right=std::min(K-1,k+8);
      float broad=clip(((excessSum[right+1]-excessSum[left])/(right-left+1)-.3f)/.5f);
      float attenuation=burst*broad*(1-.9f*tonal);
      if(k<2) attenuation*=.2f;
      float desired=stationary*std::pow(10.f,-4.f*attenuation/20.f);
      // Gradual attack/release; no hard gating, EQ or amplitude normalization.
      smooth[k]+=.35f*(desired-smooth[k]);
      gains[k]=(bypass || frames<4) ? 1.f : smooth[k];
    }
    for(int k=0;k<K;++k) history[k]+=.035f*(power[k]-history[k]);
    for(int k=0;k<K;++k) {
      float g=gains[k];
      if(k>0 && k<K-1) g=(gains[k-1]+2*g+gains[k+1])*.25f;
      real[k]*=g; imag[k]*=g;
      if(k>0 && k<N/2) {real[N-k]=real[k];imag[N-k]=-imag[k];}
    }
    fft(true);
    for(int i=0;i<H;++i) {
      output[i]=tail[i]+real[i]*window[i];
      tail[i]=real[i+H]*window[i+H];
    }
    ++frames;
  }
 private:
  static constexpr float kPi=3.14159265358979323846f;
  float previous[H]={},tail[H]={},window[N]={};
  float real[N]={},imag[N]={},power[K]={},noise[K]={},history[K]={},smooth[K]={},gains[K]={};
  float powerSum[K+1]={},excessSum[K+1]={},excess[K]={};
  uint32_t frames=0;
  static float clip(float a) {return std::max(0.f,std::min(1.f,a));}
  void fft(bool inverse) {
    for(int i=1,j=0;i<N;++i) {
      int bit=N>>1; for(;j&bit;bit>>=1) j^=bit; j^=bit;
      if(i<j) {std::swap(real[i],real[j]);std::swap(imag[i],imag[j]);}
    }
    for(int len=2;len<=N;len<<=1) {
      float angle=(inverse?2:-2)*kPi/len,wr0=std::cos(angle),wi0=std::sin(angle);
      for(int i=0;i<N;i+=len) {
        float wr=1,wi=0;
        for(int j=0;j<len/2;++j) {
          int a=i+j,b=a+len/2;
          float br=real[b]*wr-imag[b]*wi,bi=real[b]*wi+imag[b]*wr;
          real[b]=real[a]-br;imag[b]=imag[a]-bi;real[a]+=br;imag[a]+=bi;
          float next=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=next;
        }
      }
    }
    if(inverse) for(int i=0;i<N;++i) {real[i]/=N;imag[i]/=N;}
  }
};
