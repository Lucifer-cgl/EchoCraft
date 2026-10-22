#pragma once
#include <cmath>

// Prototype-specific 125/250 Hz peaks measured in a quiet microphone capture.
// Narrow Q=60 notches, blended 50% dry: bounded to about 6 dB at each centre.
// This does not identify electrical faults or remove broadband microphone noise.
class HumReducer {
 public:
  void reset() {first.init(125.f);second.init(250.f);mix=1.f;}
  float process(float sample,bool enabled) {
    float filtered=second.run(first.run(sample));
    mix+=.002f*((enabled?1.f:0.f)-mix);
    // Exact dry output in bypass; filters keep tracking to avoid stale state.
    return enabled?sample+.5f*mix*(filtered-sample):sample;
  }
 private:
  struct Notch {
    float b0=1,b1=0,b2=0,a1=0,a2=0,z1=0,z2=0;
    void init(float hz) {
      const float omega=2.f*3.14159265358979323846f*hz/32000.f;
      const float alpha=std::sin(omega)/(2.f*60.f),a0=1.f+alpha;
      b0=1.f/a0;b1=-2.f*std::cos(omega)/a0;b2=b0;a1=b1;a2=(1.f-alpha)/a0;z1=z2=0;
    }
    float run(float x) {float y=b0*x+z1;z1=b1*x-a1*y+z2;z2=b2*x-a2*y;return y;}
  } first,second;
  float mix=1.f;
};
