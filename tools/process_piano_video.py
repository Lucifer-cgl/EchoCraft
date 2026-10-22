"""Experimental, bounded spectral attenuation for music without a clean noise reference."""
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
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source')
    args = parser.parse_args()
    source = Path(args.source).resolve()
    folder = source.parent / 'recordings' / ('piano-test-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    folder.mkdir(parents=True)
    start = time.perf_counter()
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    run('ffmpeg', '-v', 'error', '-nostdin', '-n', '-i', str(source), '-map', '0:a:0',
        '-c:a', 'pcm_f32le', str(folder / 'original-float.wav'))
    rate, x = wavfile.read(folder / 'original-float.wav')
    if x.ndim == 1:
        x = x[:, None]
    options = dict(fs=rate, window='hann', nperseg=2048, noverlap=1536)
    # Shared mask from channel energy avoids changing stereo image independently.
    power = None
    for channel in range(x.shape[1]):
        _, _, spectrum = signal.stft(x[:, channel], **options)
        p = abs(spectrum) ** 2
        power = p if power is None else power + p
    power /= x.shape[1]
    del spectrum, p
    # This is an estimate, NOT a confirmed noise-only interval.
    noise = np.quantile(power, 0.15, axis=1, keepdims=True)
    noise = ndimage.uniform_filter1d(noise, size=5, axis=0)
    local = ndimage.median_filter(power, size=(17, 1), mode='nearest')
    # Protect narrow spectral ridges, including piano harmonics, at the cost of retaining tonal noise.
    protection = np.clip((power / np.maximum(local, 1e-20) - 2) / 4, 0, 1)
    report = dict(source=str(source), source_sha256=source_hash, sample_rate=rate,
                  frames=len(x), channels=x.shape[1], duration_seconds=len(x)/rate,
                  method='15th-percentile noise estimate; shared stereo spectral mask; narrow-ridge protection',
                  warning='No clean reference or isolated piano ground truth. No claim of removing all friction, water, or fan sounds.', variants={})
    # Common original level for fair comparisons. No limiter, speech model, high-pass or hard gate.
    rms = lambda a: float(np.sqrt(np.mean(np.square(a, dtype=np.float64))))
    original_rms = rms(x)
    for name, limit, amount in [('gentle', 6, 0.7), ('moderate', 10, 1.1)]:
        stage = time.perf_counter()
        gain = np.sqrt(np.maximum(0, 1 - amount * noise / np.maximum(power, 1e-20)))
        gain = np.maximum(gain, 10 ** (-limit / 20))
        gain = gain + protection * (1 - gain) * 0.85
        gain = ndimage.uniform_filter(gain, size=(3, 5), mode='nearest')
        y = np.empty_like(x)
        for channel in range(x.shape[1]):
            _, _, spectrum = signal.stft(x[:, channel], **options)
            _, audio = signal.istft(spectrum * gain, **options)
            y[:, channel] = audio[:len(x)]
        match = original_rms / max(rms(y), 1e-12)
        # One common headroom factor applied identically to original and processed comparison.
        headroom = min(1.0, 0.95 / max(float(np.max(abs(x))), float(np.max(abs(y * match))), 1e-12))
        def save(filename, samples):
            wavfile.write(folder / filename, rate, np.clip(np.rint(samples * 32768), -32768, 32767).astype(np.int16))
        save(name + '.wav', y)
        save(name + '-compare.wav', y * match * headroom)
        save(name + '-original-compare.wav', x * headroom)
        # Preserve the original video stream. Replace only audio, with original-level processed PCM.
        run('ffmpeg', '-v', 'error', '-nostdin', '-n', '-i', str(source), '-i', str(folder / (name + '.wav')),
            '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '192k',
            '-map_metadata', '0', str(folder / (name + '-video.mp4')))
        # 20-second previews, starting at 10 seconds (full versions are also retained).
        for suffix in ['-compare', '-original-compare']:
            run('ffmpeg', '-v', 'error', '-nostdin', '-n', '-ss', '10', '-i', str(folder / (name + suffix + '.wav')),
                '-t', '20', '-c:a', 'pcm_s16le', str(folder / (name + suffix + '-preview.wav')))
        report['variants'][name] = dict(max_attenuation_db=limit,
            match_gain_db=float(20*np.log10(match)), common_headroom_gain_db=float(20*np.log10(headroom)),
            processing_seconds=time.perf_counter()-stage, output_frames=len(y),
            sample_change_rms=rms(y-x))
        print(name + ' complete', flush=True)
    report['total_seconds'] = time.perf_counter() - start
    assert hashlib.sha256(source.read_bytes()).hexdigest() == source_hash
    (folder / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    (folder / '说明.txt').write_text(
        '钢琴降噪试验，原视频未修改。\n'
        '先听 gentle-original-compare-preview.wav，再听 gentle-compare-preview.wav（10～30秒）。\n'
        'gentle 为轻度，moderate 为较强试验。完整 compare 音频已按全段 RMS 对齐，再应用共同防削波系数。\n'
        '每个强度使用自己对应的 original-compare 作对照；没有人声增强或高通滤波。\n'
        '*-video.mp4 保留原画面码流，替换处理后的音轨，没有套用试听匹配增益。\n'
        '没有确认的纯噪声区间，使用自动低分位估计，可能将持续琴音当作噪声。\n'
        '窄频峰保护会同时保留部分风扇音；摩擦、流水等非稳定噪声可能残留。\n'
        '这是待试听的候选结果，不代表已验证音质改善。\n', encoding='utf-8')
    print(folder, flush=True)


if __name__ == '__main__':
    main()
