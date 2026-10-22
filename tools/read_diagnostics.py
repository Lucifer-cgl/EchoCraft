"""Read a bounded diagnostic window without holding the serial port open."""
import argparse
import time
from pathlib import Path

import serial

parser = argparse.ArgumentParser()
parser.add_argument('--port', default='COM9')
parser.add_argument('--seconds', type=float, default=10)
parser.add_argument('--output')
args = parser.parse_args()
lines = []
with serial.Serial(args.port, 115200, timeout=1) as connection:
    deadline = time.monotonic() + args.seconds
    while time.monotonic() < deadline:
        line = connection.readline().decode('utf-8', errors='replace').strip()
        if line:
            lines.append(line)
            print(line, flush=True)
if args.output:
    Path(args.output).write_text('\n'.join(lines) + '\n', encoding='utf-8')
