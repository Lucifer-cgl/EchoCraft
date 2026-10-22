"""Exercise the actual ESP32 DSP using digital PCM replay, without acoustic rerecording."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import struct
import time

import numpy as np
from scipy import signal
from scipy.io import wavfile
import serial


def replay(connection, pcm, bypass):
    n=len(pcm)
    padded=np.pad(pcm,(0,256 + (-n % 256))).astype('<i2')
    connection.reset_input_buffer()
    connection.write((b'B' if bypass else b'D')+b'T'+struct.pack('<I',len(padded)))
    deadline=time.monotonic()+5
    while connection.readline()!=b'ECREPLAY1\n':
        if time.monotonic()>deadline:
            raise RuntimeError('No board replay header')
    data=bytearray()
    for i in range(0,len(padded),256):
        connection.write(padded[i:i+256].tobytes())
        block=connection.read(512)
        if len(block)!=512:
            raise RuntimeError(f'Incomplete board response at sample {i}')
        data.extend(block)
        if i and i % 320000 == 0:
            print(f'Board replay: {i/32000:.0f}/{n/32000:.1f} audio seconds',flush=True)
    assert connection.readline()==b'\n'
    footer=connection.readline().decode().strip()
    match=re.fullmatch(r'REPLAY_END max_dsp_us=(\d+)',footer)
    if not match:
        raise RuntimeError(f'Invalid replay footer: {footer}')
    # Discard the known 256-sample overlap-add latency; retain exact input duration.
    return np.frombuffer(data,dtype='<i2')[256:256+n].copy(),int(match[1])


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',default='COM9')
    parser.add_argument('--output-gain',type=float,default=3.0,help='Firmware output gain (use 1 for older firmware)')
    parser.add_argument('--case',action='store_true')
    parser.add_argument('--case-seconds',type=float,help='Optional excerpt length for a quick board regression')
    args=parser.parse_args()
    if not np.isfinite(args.output_gain) or args.output_gain<=0:
        parser.error('--output-gain must be positive and finite')
    root=Path(__file__).resolve().parents[1]
    folder=root/'recordings'/('board-dsp-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(parents=True)
    fs=32000
    rng=np.random.default_rng(42)
    t=np.arange(fs*4)/fs
    x=rng.normal(0,.008,len(t))
    active=t>=1
    x[active]+=.12*np.sin(2*np.pi*1000*t[active])
    pcm=np.round(x*32768).astype('<i2')
    with serial.Serial(args.port,921600,timeout=3) as connection:
        time.sleep(2)
        bypass,bypass_us=replay(connection,pcm,True)
        result,process_us=replay(connection,pcm,False)
        expected=np.clip(np.rint(pcm.astype(float)*args.output_gain),-32768,32767).astype(int)
        max_error=int(np.max(abs(bypass.astype(int)-expected)))
        rms=lambda a:float(np.sqrt(np.mean(a.astype(float)**2)))
        noise_change=20*np.log10(rms(result[fs//2:fs])/rms(pcm[fs//2:fs])/args.output_gain)
        def tone(a):
            return abs(np.mean(a[2*fs:]*np.exp(-2j*np.pi*1000*t[2*fs:])))
        tone_change=20*np.log10(tone(result)/tone(pcm)/args.output_gain)
        report=dict(bypass_max_error_lsb=max_error,max_bypass_us=bypass_us,max_dsp_us=process_us,
            output_gain=args.output_gain,hop_budget_us=8000,noise_change_db=float(noise_change),tone_change_db=float(tone_change))
        (folder/'synthetic-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print(json.dumps(report,indent=2),flush=True)
        assert max_error<=3,'Bypass reconstruction/transport failed'
        assert process_us<8000,'DSP exceeds real-time hop budget'
        assert noise_change < -.5,'No useful suppression in synthetic noise interval'
        assert abs(tone_change)<2,'Too much target-tone attenuation'
        if args.case:
            source=root/'tests'/'audio'/'case-001'/'original.wav'
            rate,original=wavfile.read(source)
            if args.case_seconds is not None:
                if args.case_seconds <= 0:
                    raise ValueError('case-seconds must be positive')
                original=original[:int(rate*args.case_seconds)]
            if original.ndim>1:
                original=original.mean(axis=1)
            # Current hardware microphone is mono 32k; downmix/resample reference accordingly.
            original=signal.resample_poly(original,320,441)
            original=np.clip(np.rint(original*32768),-32768,32767).astype('<i2')
            processed,max_us=replay(connection,original,False)
            wavfile.write(folder/'case-original-32k-mono.wav',fs,original)
            wavfile.write(folder/'case-board-denoised.wav',fs,processed)
            match=rms(original)/max(rms(processed),1e-12)
            head=min(1,.89*32768/max(np.max(abs(original.astype(float))),np.max(abs(processed.astype(float)*match))))
            for name,a in [('original',original.astype(float)*head),('denoised',processed.astype(float)*match*head)]:
                wavfile.write(folder/('case-'+name+'-compare.wav'),fs,np.clip(np.rint(a),-32768,32767).astype('<i2'))
            (folder/'case-report.json').write_text(json.dumps(dict(source=str(source),frames=len(original),
                rate=fs,max_dsp_us=max_us,match_gain_db=20*np.log10(match),delay_compensation_samples=256,
                excerpt_seconds=args.case_seconds,
                note='Actual board replay, not microphone capture; resampled mono reference. Audition still required.'),indent=2),encoding='utf-8')
            assert len(processed)==len(original) and max_us<8000
    print(folder,flush=True)


if __name__=='__main__':
    main()
