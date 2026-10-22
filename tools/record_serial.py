"""Capture mic-record or mic-denoise firmware's fixed 10-second PCM response as WAV."""
import argparse
from array import array
import json
import math
from pathlib import Path
import sys
import time
import wave
import serial

parser = argparse.ArgumentParser()
parser.add_argument('--port', default='COM9')
parser.add_argument('--output', required=True)
parser.add_argument('--bypass', action='store_true', help='Disable board denoising for comparison')
args = parser.parse_args()
target = Path(args.output).resolve()
# Keep command-line captures grouped too, including legacy --output recordings/name.wav.
recordings_root = Path(__file__).resolve().parents[1] / 'recordings'
if target.parent == recordings_root:
    target = target.parent / target.stem / target.name
target.parent.mkdir(parents=True, exist_ok=True)
if target.exists() or target.with_name(target.stem + '-listen.wav').exists():
    raise SystemExit('Output already exists; choose a fresh name.')

with serial.Serial(args.port, 921600, timeout=1) as connection:
    time.sleep(2)
    connection.reset_input_buffer()
    print('Recording starts in 3 seconds...', flush=True)
    time.sleep(3)
    connection.write(b'BR' if args.bypass else b'DR')
    deadline = time.monotonic() + 5
    while True:
        line = connection.readline()
        if line == b'ECPCM1\n':
            break
        if time.monotonic() > deadline:
            raise SystemExit('No recording header received. No WAV generated.')
    print('RECORDING NOW: 10 seconds', flush=True)
    start = time.monotonic()
    audio = bytearray()
    while len(audio) < 640000:
        chunk = connection.read(min(8192, 640000 - len(audio)))
        audio.extend(chunk)
        if time.monotonic() - start > 18:
            raise SystemExit(f'Incomplete recording: {len(audio)}/640000 bytes')
    if connection.read(5) != b'\nEND\n':
        raise SystemExit('Recording footer invalid. No WAV generated.')
    elapsed = time.monotonic() - start
    trailer = connection.readline().decode('utf-8', errors='replace').strip()
    firmware_info = json.loads(trailer[6:]) if trailer.startswith('ECDSP ') else None

def save(path, data):
    with wave.open(str(path), 'wb') as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(32000)
        out.writeframes(data)

save(target, audio)
values = array('h')
values.frombytes(audio)
if sys.byteorder != 'little':
    values.byteswap()
peak = max(abs(v) for v in values)
rms = math.sqrt(sum(v * v for v in values) / len(values))
gain = min(100.0, (32767 * 0.89 / peak) if peak else 1.0)
gain = max(1.0, gain)
boosted = array('h', (max(-32768, min(32767, round(v * gain))) for v in values))
if sys.byteorder != 'little':
    boosted.byteswap()
listen = target.with_name(target.stem + '-listen.wav')
save(listen, boosted.tobytes())
report = dict(sample_rate=32000, duration_seconds=10, frames=len(values),
              peak_dbfs=20 * math.log10(max(peak, 1) / 32768),
              rms_dbfs=20 * math.log10(max(rms, 1e-12) / 32768),
              listen_gain_db=20 * math.log10(gain),
              clipped_samples=sum(abs(v) >= 32767 for v in values),
              transfer_seconds=elapsed, raw=str(target), listen=str(listen), firmware=firmware_info)
if firmware_info:
    report['capture_valid'] = (firmware_info['overflows'] == 0 and firmware_info['read_errors'] == 0
                               and firmware_info['max_dsp_us'] < firmware_info['hop_budget_us'])
target.with_suffix('.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2), flush=True)
