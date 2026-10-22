@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\pythonw.exe" (
  echo Project Python environment missing.
  pause
  exit /b 1
)
start "EchoCraft" ".venv\Scripts\pythonw.exe" "tools\recorder_window.py"
