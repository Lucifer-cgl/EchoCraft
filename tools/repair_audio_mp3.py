"""Experimental stationary suppression and bounded local spectral transient repair."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import time

import numpy as np
from scipy import signal, ndimage
from scipy.io import wavfile


def run(*args):
    result = subprocess.run(args, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    source = Path(parser.parse_args().source).resolve()
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    folder = source.parent / 'recordings' / ('noise-repair-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(parents=True)
    start = time.perf_counter()
    run('ffmpeg', '-v', 'error', '-nostdin', '-n', '-i', str(source), '-map', '0:a:0',
        '-c:a', 'pcm_f32le', str(folder / 'source.wav'))
    rate, x = wavfile.read(folder / 'source.wav')
    if x.ndim == 1:
        x = x[:, None]
    options = dict(fs=rate, window='hann', nperseg=2048, noverlap=1536)
    power = None
    for ch in range(x.shape[1]):
        frequencies, times, spectrum = signal.stft(x[:, ch], **options)
        p = abs(spectrum) ** 2
        power = p if power is None else power + p
    power /= x.shape[1]
    del spectrum, p
    eps = 1e-20
    noise = ndimage.uniform_filter1d(np.quantile(power, .15, axis=1, keepdims=True), 5, axis=0)
    stationary = np.maximum(np.sqrt(np.maximum(0, 1 - .8 * noise / np.maximum(power, eps))), 10 ** (-6/20))
    across_frequency = ndimage.median_filter(power, size=(17, 1), mode='nearest')
    tonal = np.clip((power / np.maximum(across_frequency, eps) - 2) / 4, 0, 1)
    # Candidate short-time excess, not semantic identification of chair or impact sounds.
    temporal_background = ndimage.median_filter(power, size=(1, 31), mode='nearest')
    excess = np.clip((10*np.log10(np.maximum(power, eps)/np.maximum(temporal_background, eps))-8)/12, 0, 1)
    broadband_support = ndimage.uniform_filter1d(excess, size=31, axis=0)
    # Suppress only locally excessive broad regions, with strong protection for tonal ridges.
    candidate = excess * np.clip((broadband_support-.25)/.5, 0, 1) * (1-.9*tonal)
    candidate[frequencies < 120, :] *= .2
    local_gain = 10 ** (-8*candidate/20)
    stationary += .85*tonal*(1-stationary)
    gain = ndimage.uniform_filter(stationary*local_gain, size=(3, 5), mode='nearest')
    y = np.empty_like(x)
    for ch in range(x.shape[1]):
        _, _, spectrum = signal.stft(x[:, ch], **options)
        _, repaired = signal.istft(spectrum*gain, **options)
        y[:, ch] = repaired[:len(x)]
    rms = lambda a: float(np.sqrt(np.mean(np.square(a, dtype=np.float64))))
    match = rms(x)/max(rms(y), 1e-12)
    headroom = min(1, .89/max(float(np.max(abs(x))), float(np.max(abs(y*match))), 1e-12))
    for name, data in [('original', x*headroom), ('denoised', y*match*headroom)]:
        assert len(data)==len(x) and np.isfinite(data).all()
        pcm = np.clip(np.rint(data*32768), -32768, 32767).astype(np.int16)
        assert not np.any(np.abs(pcm.astype(np.int32))>=32767)
        wavfile.write(folder/(name+'.wav'), rate, pcm)
        run('ffmpeg','-v','error','-nostdin','-n','-i',str(folder/(name+'.wav')),
            '-c:a','libmp3lame','-b:a','192k',str(folder/(name+'.mp3')))
    scores = np.mean(candidate, axis=0)
    regions=[]
    active=False
    for i, value in enumerate(scores):
        flag = value>.04
        if flag and not active:
            begin = max(0, float(times[i])-1024/rate)
            active=True
        if active and (not flag or i==len(scores)-1):
            regions.append([round(begin,3), round(min(len(x)/rate,float(times[i])+1024/rate),3)])
            active=False
    report = dict(source=str(source),sha256=digest,sample_rate=rate,frames=len(x),channels=x.shape[1],
        duration_seconds=len(x)/rate,processing_seconds=time.perf_counter()-start,
        processed_rms_match_db=float(20*np.log10(match)),common_gain_db=float(20*np.log10(headroom)),
        candidate_regions_seconds=regions,
        method='6 dB bounded stationary spectral suppression + up to 8 dB local excess attenuation with tonal protection',
        limitation='Heuristic candidates, not verified noise labels. Piano attacks may be affected; overlapping or tonal friction can remain. No reconstruction of known clean ground truth.')
    (folder/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    (folder/'说明.txt').write_text('original.mp3：原声对照。denoised.mp3：自动降噪和局部杂音衰减试验版。\n'
        '均保持全长和双声道，处理版按全段 RMS 对齐，之后共同留出峰值余量。\n'
        '不是人声增强，没有裁掉时间片段。局部异常区域仅为自动候选，不保证就是杂音。\n'
        '可能损伤琴音起音，也可能残留吱声；需要试听确认，不能保证无损抹除。\n',encoding='utf-8')
    assert hashlib.sha256(source.read_bytes()).hexdigest()==digest
    print(json.dumps({'folder':str(folder),'duration':len(x)/rate,'candidates':len(regions),
                      'seconds':report['processing_seconds']},indent=2))


if __name__=='__main__':
    main()
