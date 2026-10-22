"""Local one-click recorder for the EchoCraft mic-record firmware."""
from datetime import datetime
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import ttk, messagebox
from serial.tools import list_ports

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'recordings'


class Recorder:
    def __init__(self, window):
        self.window = window
        self.events = queue.Queue()
        self.busy = False
        self.latest = None
        window.title('EchoCraft 麦克风录音')
        window.geometry('650x530')
        window.minsize(540, 400)
        frame = ttk.Frame(window, padding=22)
        frame.pack(fill='both', expand=True)
        ttk.Label(frame, text='EchoCraft · 麦克风测试', font=('Microsoft YaHei UI', 18)).pack(anchor='w')
        ttk.Label(frame, text='点击后准备约 5 秒，再录 10 秒。保持 USB 连接。').pack(anchor='w', pady=10)
        row = ttk.Frame(frame)
        row.pack(fill='x')
        ttk.Label(row, text='设备串口：').pack(side='left')
        self.port = ttk.Combobox(row, width=14, state='readonly')
        self.port.pack(side='left')
        self.refresh_button = ttk.Button(row, text='刷新设备', command=self.refresh)
        self.refresh_button.pack(side='left', padx=10)
        self.status = tk.StringVar(value='就绪：请连接开发板 COM 接口。')
        ttk.Label(frame, textvariable=self.status, wraplength=550).pack(anchor='w', pady=18)
        self.start_button = ttk.Button(frame, text='开始录音（10 秒）', command=self.start)
        self.start_button.pack(fill='x')
        self.progress = ttk.Progressbar(frame, mode='indeterminate')
        self.progress.pack(fill='x', pady=12)
        self.result = tk.StringVar(value='每次录音建立独立时间文件夹，保存设备输出和检测报告。\n不会自动发送。')
        ttk.Label(frame, textvariable=self.result, wraplength=550).pack(anchor='w', pady=8)
        actions = ttk.Frame(frame)
        actions.pack(side='bottom', fill='x')
        self.play_button = ttk.Button(actions, text='播放最近试听', command=self.play, state='disabled')
        self.play_button.pack(side='left')
        ttk.Button(actions, text='打开录音文件夹', command=self.open_folder).pack(side='left', padx=8)
        self.copy_button = ttk.Button(actions, text='复制结果给我', command=self.copy, state='disabled')
        self.copy_button.pack(side='left')
        ttk.Button(frame, text='降噪对比：选择已录制的 WAV', command=self.denoise).pack(fill='x', pady=6)
        self.board_denoise = tk.BooleanVar(value=True)
        self.denoise_check = ttk.Checkbutton(frame, text='开发板实时降噪（取消为原声旁路；需新版固件）', variable=self.board_denoise)
        self.denoise_check.pack(anchor='w')
        window.protocol('WM_DELETE_WINDOW', self.close)
        self.refresh()
        window.after(100, self.poll)

    def refresh(self):
        ports = list(list_ports.comports())
        self.port['values'] = [p.device for p in ports]
        preferred = next((p.device for p in ports if p.vid == 0x1A86), None)
        if self.port.get() not in self.port['values']:
            self.port.set(preferred or (ports[0].device if ports else ''))

    def start(self):
        if self.busy:
            return
        if not self.port.get():
            messagebox.showinfo('未找到设备', '连接开发板 COM 接口后，点击刷新设备。')
            return
        self.busy = True
        self.start_button['state'] = 'disabled'
        self.refresh_button['state'] = 'disabled'
        self.port['state'] = 'disabled'
        self.denoise_check['state'] = 'disabled'
        self.status.set('正在准备，看到“正在录音”后开始说话。')
        self.progress.start(12)
        session = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
        target = OUTPUT / session / (session + '.wav')
        threading.Thread(target=self.worker, args=(self.port.get(), target, self.board_denoise.get()), daemon=True).start()

    def worker(self, port, target, denoise):
        python = ROOT / '.venv' / 'Scripts' / 'python.exe'
        logs = []
        try:
            command = [str(python), '-u', str(ROOT / 'tools' / 'record_serial.py'),
                       '--port', port, '--output', str(target)]
            if not denoise:
                command.append('--bypass')
            with subprocess.Popen(command,
                                  cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  encoding='utf-8', errors='replace',
                                  creationflags=subprocess.CREATE_NO_WINDOW) as process:
                for line in process.stdout:
                    logs.append(line)
                    if 'Recording starts' in line:
                        self.events.put(('status', '准备倒计时 3 秒……'))
                    elif 'RECORDING NOW' in line:
                        self.events.put(('status', '正在录音：先安静两秒，再说话，最后恢复安静。'))
                code = process.wait()
            target.parent.mkdir(parents=True, exist_ok=True)
            target.with_name('capture.log').write_text(''.join(logs), encoding='utf-8')
            if code:
                raise RuntimeError(''.join(logs)[-1600:])
            report = json.loads(target.with_suffix('.json').read_text(encoding='utf-8'))
            self.events.put(('done', (target, report)))
        except Exception as error:
            self.events.put(('error', str(error)))

    def poll(self):
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == 'status':
                    self.status.set(payload)
                    continue
                self.busy = False
                self.progress.stop()
                self.start_button['state'] = 'normal'
                self.refresh_button['state'] = 'normal'
                self.port['state'] = 'readonly'
                self.denoise_check['state'] = 'normal'
                if kind == 'done':
                    target, report = payload
                    self.latest = report
                    self.status.set('录音完成。试听前请先调低扬声器音量。')
                    self.summary = (f'录音：{target}\n试听：{report["listen"]}\n'
                                    f'时长：10 秒；峰值：{report["peak_dbfs"]:.1f} dBFS；'
                                    f'RMS：{report["rms_dbfs"]:.1f} dBFS\n'
                                    f'满幅样本：{report["clipped_samples"]}；试听增益：'
                                    f'{report["listen_gain_db"]:.1f} dB（未降噪）')
                    firmware = report.get('firmware')
                    if firmware:
                        self.summary = self.summary.replace('（未降噪）', '（仅试听增益）')
                        self.summary += f'\n板上模式：{firmware["mode"]}；丢缓冲：{firmware["overflows"]}；读错误：{firmware["read_errors"]}'
                        if not report.get('capture_valid', False):
                            self.status.set('已保存，但捕获检测异常：可能丢缓冲或处理超时，请勿当作合格录音。')
                    self.result.set(self.summary)
                    target.with_suffix('.txt').write_text(self.summary, encoding='utf-8')
                    self.play_button['state'] = self.copy_button['state'] = 'normal'
                else:
                    self.status.set('录音失败。请检查连接、是否占用串口，以及是否已烧录录音版固件。')
                    messagebox.showerror('录音失败', payload)
        except queue.Empty:
            pass
        self.window.after(100, self.poll)

    def play(self):
        if self.latest:
            os.startfile(self.latest['listen'])

    def denoise(self):
        subprocess.Popen([str(ROOT / '.venv' / 'Scripts' / 'pythonw.exe'),
                          str(ROOT / 'tools' / 'denoise_compare.py'), '--pick'], cwd=ROOT,
                         creationflags=subprocess.CREATE_NO_WINDOW)

    def open_folder(self):
        OUTPUT.mkdir(exist_ok=True)
        folder = Path(self.latest['raw']).parent if self.latest else OUTPUT
        os.startfile(str(folder))

    def copy(self):
        self.window.clipboard_clear()
        self.window.clipboard_append(self.summary)
        self.status.set('结果和文件路径已复制，可以粘贴到聊天中；音频保存在本机。')

    def close(self):
        if self.busy:
            messagebox.showinfo('正在录音', '请等本次录音结束后再关闭窗口。')
        else:
            self.window.destroy()


if __name__ == '__main__':
    root = tk.Tk()
    app = Recorder(root)
    if '--smoke-test' in sys.argv:
        root.after(500, root.destroy)
    root.mainloop()
