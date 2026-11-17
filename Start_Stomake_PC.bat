@echo off
setlocal
cd /d "%~dp0"
if exist "dist\Stomake.exe" (
  start "" "dist\Stomake.exe"
  exit /b 0
)
if exist "Stomake.exe" (
  start "" "Stomake.exe"
  exit /b 0
)
echo Stomake.exe belum dibuat.
echo Buka halaman GitHub Actions dan unduh artifact Stomake-PC-Windows.
pause
