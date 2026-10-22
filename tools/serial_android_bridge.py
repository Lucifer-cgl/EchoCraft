"""Loopback-only, continuous hardware PCM relay over adb reverse. No phone microphone."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import socket
import struct
import threading
import time
import wave
import zlib

import serial

MAGIC=struct.pack('<I',0x45434350)
ROOT=Path(__file__).resolve().parents[1]


def serve_client(client, port):
    stop=threading.Event();stream=threading.Event();write_lock=threading.Lock()
    session=None;wav=None
    stats={'packets':0,'frames':0,'denoise_packets':0,'bypass_packets':0,'sequence_errors':0,'crc_errors':0,'board_overflow_packets':0}
    started=None;expected=None
    def command_loop():
        try:
            while not stop.is_set():
                commands=client.recv(64)
                if not commands:break
                for command in commands:
                    with write_lock:
                        if command in b'DB':port.write(bytes([command]))
                        elif command==ord('G'):
                            port.reset_input_buffer();port.write(b'P');stream.set()
                        elif command==ord('S'):
                            stream.clear();port.write(b'Q')
        except (OSError,serial.SerialException):pass
        finally:stop.set()
    def exact(count):
        data=bytearray();deadline=time.monotonic()+2
        while len(data)<count:
            if stop.is_set():raise InterruptedError()
            chunk=port.read(count-len(data));data.extend(chunk)
            if time.monotonic()>deadline:raise TimeoutError('Board PCM stream timeout')
        return bytes(data)
    client.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
    client.sendall(b'ECHOBRIDGE1\n')
    worker=threading.Thread(target=command_loop,daemon=True);worker.start()
    try:
        while not stop.is_set():
            if not stream.wait(.1):continue
            if session is None:
                session=ROOT/'recordings'/('bridge-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f'));session.mkdir(parents=True)
                wav=wave.open(str(session/'device-audio.wav'),'wb');wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(32000)
                started=time.monotonic();print('STREAM '+str(session),flush=True)
            magic=bytearray(exact(4))
            scanned=0
            while bytes(magic)!=MAGIC:
                magic=magic[1:]+exact(1);scanned+=1
                if scanned>65536:raise ValueError('No valid packet header')
            header=exact(16)
            sequence,index,frames,flags,crc=struct.unpack('<IIHHI',header)
            if frames!=256:raise ValueError('Unsupported board packet size')
            payload=exact(frames*2)
            if zlib.crc32(payload)!=crc:stats['crc_errors']+=1;raise ValueError('Board CRC mismatch')
            if expected is not None and sequence!=expected:stats['sequence_errors']+=1;raise ValueError('Board packet sequence gap')
            expected=(sequence+1)&0xffffffff
            if flags&2:stats['board_overflow_packets']+=1;raise ValueError('Board reported I2S overflow')
            client.sendall(MAGIC+header+payload)
            wav.writeframesraw(payload);stats['frames']+=frames;stats['packets']+=1
            stats['denoise_packets' if flags&1 else 'bypass_packets']+=1
    except InterruptedError:pass
    except Exception as error:
        stats['error']=str(error);print('STREAM ERROR '+str(error),flush=True)
    finally:
        stop.set();stream.clear()
        try:port.write(b'Q')
        except Exception:pass
        try:client.shutdown(socket.SHUT_RDWR)
        except OSError:pass
        client.close();worker.join(2)
        if wav:wav.close()
        if session:
            stats['source']='ESP32-S3 COM PCM, forwarded via adb reverse; never phone microphone'
            stats['duration_seconds']=stats['frames']/32000
            stats['elapsed_seconds']=time.monotonic()-started
            stats['valid']=not stats.get('error') and stats['frames']>0
            (session/'report.json').write_text(json.dumps(stats,indent=2),encoding='utf-8')
            print('END '+json.dumps(stats),flush=True)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--port',default='COM9');parser.add_argument('--listen',type=int,default=28732)
    args=parser.parse_args()
    with serial.Serial(args.port,921600,timeout=.1,write_timeout=2) as port:
        time.sleep(2);port.reset_input_buffer();port.write(b'Q')
        with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as server:
            server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);server.bind(('127.0.0.1',args.listen));server.listen(1)
            print(f'READY 127.0.0.1:{args.listen} -> {args.port}',flush=True)
            while True:
                client,_=server.accept();serve_client(client,port)


if __name__=='__main__':main()
