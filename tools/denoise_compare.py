"""Conservative stationary-noise reduction with explicit noise-only reference."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import wave

import numpy as np
from scipy import signal, ndimage


def load(path):
    with wave.open(str(path), 'rb') as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise ValueError('Only mono 16-bit PCM WAV is supported.')
        rate = wav.getframerate()
        x = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype(float) / 32768
    if len(x) < 2048:
        raise ValueError('Recording too short.')
    return rate, x


def reduce_noise(x, rate, start, end, limit_db):
    a, b = int(start * rate), int(end * rate)
    if not (0 <= a < b <= len(x)) or b - a < rate // 2:
        raise ValueError('Select at least 0.5 seconds of noise only inside the recording.')
    options = dict(fs=rate, window='hann', nperseg=1024, noverlap=768)
    _, _, reference = signal.stft(x[a:b], **options, boundary=None)
    noise = np.mean(abs(reference) ** 2, axis=1, keepdims=True)
    _, _, spectrum = signal.stft(x, **options)
    power = abs(spectrum) ** 2
    gain = np.sqrt(np.maximum(0, 1 - noise / np.maximum(power, 1e-24)))
    gain = np.clip(gain, 10 ** (-limit_db / 20), 1)
    gain = ndimage.uniform_filter(gain, size=(3, 5), mode='nearest')
    _, result = signal.istft(spectrum * gain, **options)
    return result[:len(x)]


def save(path, x, rate):
    with wave.open(str(path), 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        wav.writeframes(np.clip(np.rint(x * 32768), -32768, 32767).astype('<i2').tobytes())


def compare(source, start, end, active_start, active_end):
    source = Path(source).resolve()
    rate, x = load(source)
    aa, ab = int(active_start * rate), int(active_end * rate)
    if not (0 <= aa < ab <= len(x)) or ab - aa < rate // 2:
        raise ValueError('Select at least 0.5 seconds of target sound.')
    if max(start, active_start) < min(end, active_end):
        raise ValueError('Noise reference and target sound must not overlap.')
    variants = {'original': x}
    for name, db in [('gentle', 6), ('moderate', 12)]:
        variants[name] = reduce_noise(x, rate, start, end, db)
    folder = source.parent / ('denoise-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    rms = lambda v: float(np.sqrt(np.mean(v ** 2)))
    reference_rms = rms(x[aa:ab])
    if reference_rms < 1e-9:
        raise ValueError('Target sound region is silent.')
    folder.mkdir()
    # Match target-region RMS first, then use ONE common playback gain/headroom.
    factors = {name: reference_rms / max(rms(y[aa:ab]), 1e-12) for name, y in variants.items()}
    peak = max(float(np.max(abs(y * factors[name]))) for name, y in variants.items())
    common_gain = min(100, 0.89 / max(peak, 1e-12))
    report = {'source': str(source), 'sample_rate': rate,
              'noise_seconds': [start, end], 'target_seconds': [active_start, active_end],
              'common_playback_gain_db': float(20 * np.log10(common_gain)), 'variants': {}}
    na, nb = int(start * rate), int(end * rate)
    for name, y in variants.items():
        save(folder / (name + '.wav'), y, rate)
        save(folder / (name + '-compare.wav'), y * factors[name] * common_gain, rate)
        before, after = rms(x[na:nb]), rms(y[na:nb] * factors[name])
        report['variants'][name] = {'target_match_gain_db': float(20 * np.log10(factors[name])),
            'noise_region_level_change_db': float(20 * np.log10(max(after, 1e-12) / max(before, 1e-12)))}
    report['note'] = 'Noise-region change is not SNR improvement. Audition target detail and sustain; no distance detection or speech separation.'
    (folder / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    (folder / '说明.txt').write_text(
        '请对比 original-compare.wav、gentle-compare.wav、moderate-compare.wav。\n'
        '三个对比文件已按指定目标声音片段匹配 RMS，并使用共同播放增益。\n'
        '不带 compare 的文件保留各自处理电平。原文件未覆盖。\n'
        'gentle 最大频谱衰减 6 dB，moderate 12 dB；不加高通、不用硬噪声门。\n'
        '注意琴音尾部、拨弦瞬态和泛音是否受损；安静段变小不代表演奏中噪声已去除。\n', encoding='utf-8')
    return folder


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', nargs='?')
    parser.add_argument('--noise-start', type=float, default=0)
    parser.add_argument('--noise-end', type=float)
    parser.add_argument('--target-start', type=float)
    parser.add_argument('--target-end', type=float)
    parser.add_argument('--pick', action='store_true')
    args = parser.parse_args()
    if args.pick:
        import tkinter as tk
        from tkinter import filedialog, simpledialog, messagebox
        import os
        root = tk.Tk()
        root.withdraw()
        try:
            source = filedialog.askopenfilename(title='选择原始 WAV（不要选 listen 版）',
                initialdir=Path(__file__).resolve().parents[1] / 'recordings', filetypes=[('WAV', '*.wav')])
            if not source:
                return
            selection = simpledialog.askstring('选择参考区间',
                '输入四个秒数：噪声开始,噪声结束,目标声音开始,目标声音结束\n'
                '噪声段必须没有说话或乐器、也没有残留延音。\n'
                '例如：0,2,3,8。若录音不符合，请取消后重录。', initialvalue='0,2,3,8')
            if selection is None:
                return
            start, end, active_start, active_end = map(float, selection.split(','))
            folder = compare(source, start, end, active_start, active_end)
            messagebox.showinfo('处理完成', f'已生成原声、轻度、中度对比版本。\n{folder}')
            os.startfile(str(folder))
        except Exception as error:
            messagebox.showerror('处理失败', str(error))
        finally:
            root.destroy()
    else:
        if not args.source or None in (args.noise_end, args.target_start, args.target_end):
            parser.error('Provide source, --noise-end, --target-start and --target-end; or use --pick.')
        print(compare(args.source, args.noise_start, args.noise_end, args.target_start, args.target_end))


if __name__ == '__main__':
    main()
