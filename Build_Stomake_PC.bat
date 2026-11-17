@echo off
setlocal
cd /d "%~dp0"
py -3 -m venv .venv
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
python -m pip install -r requirements.txt pyinstaller
pyinstaller --noconfirm --clean --onefile --windowed --name Stomake --collect-all PySide6 --collect-all shiboken6 main.py
if errorlevel 1 (
  echo Build gagal.
  pause
  exit /b 1
)
echo Selesai: dist\Stomake.exe
pause
